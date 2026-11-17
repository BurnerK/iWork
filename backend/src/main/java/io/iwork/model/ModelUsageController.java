package io.iwork.model;

import io.iwork.identity.Caller;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class ModelUsageController {
    private final ModelCallLedger ledger;

    ModelUsageController(ModelCallLedger ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/api/tasks/{id}/calls")
    List<Map<String, Object>> calls(
            @AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return ledger.list(caller, id);
    }
}
