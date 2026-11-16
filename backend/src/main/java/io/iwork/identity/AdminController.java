package io.iwork.identity;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
class AdminController {
    private final IdentityService identity;
    AdminController(IdentityService identity) { this.identity=identity; }
    @GetMapping IdentityService.UserPage list(@AuthenticationPrincipal Caller caller,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return identity.listUsers(caller,page,size);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    IdentityService.IssuedPassword create(@AuthenticationPrincipal Caller caller, @RequestBody Map<String,Object> input) {
        if(!input.keySet().equals(java.util.Set.of("username","displayName")) || !(input.get("username") instanceof String username)
                || !(input.get("displayName") instanceof String displayName)) throw new ApiFailure(400,"invalid_input");
        return identity.createUser(caller,username,displayName);
    }
    @PatchMapping("/{id}/status") IdentityService.User status(@AuthenticationPrincipal Caller caller, @PathVariable String id, @RequestBody Map<String,Object> input) {
        if(!input.keySet().equals(java.util.Set.of("enabled")) || !(input.get("enabled") instanceof Boolean enabled)) throw new ApiFailure(400,"invalid_input");
        return identity.setEnabled(caller,id,enabled);
    }
    @PostMapping("/{id}/reset-password") IdentityService.IssuedPassword reset(@AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return identity.resetPassword(caller,id);
    }
}
