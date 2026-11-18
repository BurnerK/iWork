package io.iwork.conversation;

import io.iwork.identity.Caller;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/conversations")
class ConversationController {
    private final ConversationService service;

    ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping
    ConversationService.Page list(
            @AuthenticationPrincipal Caller caller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(caller, page, size);
    }

    @GetMapping("/{id}")
    ConversationService.Conversation open(
            @AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return service.open(caller, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConversationService.Conversation create(
            @AuthenticationPrincipal Caller caller, @RequestBody Map<String, Object> input) {
        return service.create(caller, input);
    }
}
