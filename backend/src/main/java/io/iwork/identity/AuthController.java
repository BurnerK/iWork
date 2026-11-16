package io.iwork.identity;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final IdentityService identity;
    private final Clock clock;
    AuthController(IdentityService identity, Clock clock) { this.identity=identity; this.clock=clock; }
    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token", token.getToken()); }
    @GetMapping("/me") Caller me(@AuthenticationPrincipal Caller caller) { return caller; }
    @PostMapping(value="/login", consumes="application/x-www-form-urlencoded")
    Caller login(@RequestParam String username, @RequestParam String password, HttpServletRequest request, HttpServletResponse response) {
        var caller = identity.authenticate(username, password);
        establish(caller, request, response);
        return caller;
    }
    record PasswordChange(String currentPassword, String newPassword) {}
    @PostMapping("/password") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void password(@AuthenticationPrincipal Caller caller, @RequestBody PasswordChange change,
            HttpServletRequest request, HttpServletResponse response) {
        identity.changePassword(caller, change.currentPassword(), change.newPassword());
        SecurityContextHolder.clearContext();
        var session=request.getSession(false);
        if(session!=null) session.invalidate();
    }
    private void establish(Caller caller, HttpServletRequest request, HttpServletResponse response) {
        var old = request.getSession(false);
        if(old != null) old.invalidate();
        var session = request.getSession(true);
        session.setAttribute("authenticatedAt", clock.millis());
        session.setAttribute("lastSeenAt", clock.millis());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(caller, null,
            List.of(new SimpleGrantedAuthority("ROLE_" + caller.role()))));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    }
}
