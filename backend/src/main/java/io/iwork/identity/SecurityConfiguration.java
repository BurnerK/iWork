package io.iwork.identity;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**",
                        "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                .anyRequest().denyAll())
                .headers(headers -> headers.contentSecurityPolicy(policy -> policy
                        .policyDirectives("default-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'")))
                .build();
    }
}
