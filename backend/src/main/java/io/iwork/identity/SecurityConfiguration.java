package io.iwork.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CookieSerializer cookieSerializer(@Value("${iwork.cookie-secure:true}") boolean secure) {
        var cookie = new DefaultCookieSerializer();
        cookie.setCookieName("IWORK_SESSION");
        cookie.setUseHttpOnlyCookie(true);
        cookie.setUseSecureCookie(secure);
        cookie.setSameSite("Lax");
        cookie.setCookieMaxAge((int) Duration.ofDays(30).toSeconds());
        cookie.setCookiePath("/");
        return cookie;
    }

    @Bean
    org.springframework.session.web.http.HttpSessionIdResolver sessionIdResolver(
            CookieSerializer cookies) {
        var cookiesResolver =
                new org.springframework.session.web.http.CookieHttpSessionIdResolver();
        cookiesResolver.setCookieSerializer(cookies);
        return new org.springframework.session.web.http.HttpSessionIdResolver() {
            @Override
            public java.util.List<String> resolveSessionIds(HttpServletRequest request) {
                // Framework request handling can read sessions even on public probes.
                return request.getRequestURI().startsWith("/api/")
                        ? cookiesResolver.resolveSessionIds(request)
                        : java.util.List.of();
            }

            @Override
            public void setSessionId(
                    HttpServletRequest request, HttpServletResponse response, String id) {
                cookiesResolver.setSessionId(request, response, id);
            }

            @Override
            public void expireSession(HttpServletRequest request, HttpServletResponse response) {
                cookiesResolver.expireSession(request, response);
            }
        };
    }

    @Bean
    FilterRegistrationBean<Filter> dependencyFailures(
            org.springframework.boot.availability.ApplicationAvailability availability) {
        Filter filter =
                (request, response, chain) -> {
                    var requestHttp = (HttpServletRequest) request;
                    var responseHttp = (HttpServletResponse) response;
                    boolean businessApi = requestHttp.getRequestURI().startsWith("/api/");
                    if (businessApi
                            && availability.getReadinessState()
                                    != org.springframework.boot.availability.ReadinessState
                                            .ACCEPTING_TRAFFIC) {
                        ApiFailure.write(requestHttp, responseHttp, 503, "service_unavailable");
                        return;
                    }
                    try {
                        chain.doFilter(request, response);
                    } catch (Exception error) {
                        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                            if (cause instanceof DataAccessException) {
                                if (!responseHttp.isCommitted()) {
                                    responseHttp.reset();
                                    ApiFailure.write(
                                            requestHttp, responseHttp, 503, "service_unavailable");
                                }
                                return;
                            }
                        }
                        if (businessApi
                                && !responseHttp.isCommitted()
                                && !(error instanceof IOException)) {
                            org.slf4j.LoggerFactory.getLogger(SecurityConfiguration.class)
                                    .error(
                                            "Unhandled API exception: {}",
                                            error.getClass().getName());
                            responseHttp.reset();
                            ApiFailure.write(requestHttp, responseHttp, 500, "internal_error");
                            return;
                        }
                        if (error instanceof ServletException servlet) throw servlet;
                        if (error instanceof IOException io) throw io;
                        throw new ServletException(error);
                    }
                };
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
    SecurityFilterChain publicEndpoints(HttpSecurity http) throws Exception {
        return http.securityMatcher("/", "/index.html", "/assets/**", "/actuator/**")
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(
                                        org.springframework.security.config.http
                                                .SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable())
                .anonymous(anonymous -> anonymous.disable())
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                HttpMethod.GET,
                                                "/",
                                                "/index.html",
                                                "/assets/**",
                                                "/actuator/health/liveness",
                                                "/actuator/health/readiness")
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                        (request, response, error) ->
                                                ApiFailure.write(
                                                        request, response, 403, "forbidden")))
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                        policy ->
                                                policy.policyDirectives(
                                                        "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'")))
                .build();
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
    SecurityFilterChain security(HttpSecurity http, IdentityService identity, Clock clock)
            throws Exception {
        var liveIdentity =
                new OncePerRequestFilter() {
                    @Override
                    protected boolean shouldNotFilter(HttpServletRequest request) {
                        return !request.getRequestURI().startsWith("/api/");
                    }

                    @Override
                    protected void doFilterInternal(
                            HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain chain)
                            throws ServletException, IOException {
                        var authentication = SecurityContextHolder.getContext().getAuthentication();
                        if (authentication != null
                                && authentication.getPrincipal() instanceof Caller caller) {
                            try {
                                var session = request.getSession(false);
                                Long start =
                                        session == null
                                                ? null
                                                : (Long) session.getAttribute("authenticatedAt");
                                Long last =
                                        session == null
                                                ? null
                                                : (Long) session.getAttribute("lastSeenAt");
                                if (start == null
                                        || last == null
                                        || clock.millis() - start >= Duration.ofDays(30).toMillis()
                                        || clock.millis() - last >= Duration.ofDays(7).toMillis())
                                    throw new ApiFailure(401, "session_expired");
                                var live = identity.verify(caller, false);
                                session.setAttribute("lastSeenAt", clock.millis());
                                if (live.mustChangePassword()
                                        && !Set.of(
                                                        "/api/auth/me",
                                                        "/api/auth/password",
                                                        "/api/auth/logout",
                                                        "/api/auth/csrf")
                                                .contains(request.getRequestURI()))
                                    throw new ApiFailure(403, "password_change_required");
                            } catch (ApiFailure failure) {
                                if (failure.status == 401) {
                                    SecurityContextHolder.clearContext();
                                    var session = request.getSession(false);
                                    if (session != null) session.invalidate();
                                }
                                ApiFailure.write(
                                        request, response, failure.status, failure.getMessage());
                                return;
                            }
                        }
                        chain.doFilter(request, response);
                    }
                };
        return http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/",
                                                "/index.html",
                                                "/assets/**",
                                                "/api/auth/csrf",
                                                "/actuator/health/liveness",
                                                "/actuator/health/readiness")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/auth/login")
                                        .permitAll()
                                        .requestMatchers("/api/admin/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers("/api/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, error) ->
                                                        ApiFailure.write(
                                                                request,
                                                                response,
                                                                request.getRequestURI()
                                                                                .startsWith("/api/")
                                                                        ? 401
                                                                        : 403,
                                                                "authentication_required"))
                                        .accessDeniedHandler(
                                                (request, response, error) ->
                                                        ApiFailure.write(
                                                                request,
                                                                response,
                                                                403,
                                                                "forbidden")))
                .logout(
                        logout ->
                                logout.logoutUrl("/api/auth/logout")
                                        .logoutSuccessHandler(
                                                (request, response, auth) ->
                                                        response.setStatus(204)))
                .addFilterBefore(liveIdentity, AuthorizationFilter.class)
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                        policy ->
                                                policy.policyDirectives(
                                                        "default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'")))
                .build();
    }
}
