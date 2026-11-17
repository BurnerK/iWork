package io.iwork.sandbox;

import io.iwork.identity.Caller;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class ParsingController {
    private final MaterialParsing parsing;

    ParsingController(MaterialParsing parsing) {
        this.parsing = parsing;
    }

    @GetMapping("/api/tasks/{task}/parses")
    List<Map<String, Object>> list(
            @AuthenticationPrincipal Caller caller, @PathVariable String task) {
        return parsing.list(caller, task);
    }

    @GetMapping("/api/tasks/{task}/parses/{id}")
    Map<String, Object> text(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String task,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset) {
        return parsing.text(caller, task, id, offset);
    }
}
