package io.iwork.conversation;

import io.iwork.identity.Caller;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class TaskDiagnosticsController {
    private final TaskDiagnostics diagnostics;
    TaskDiagnosticsController(TaskDiagnostics diagnostics) { this.diagnostics=diagnostics; }
    @GetMapping("/api/tasks/{id}/diagnostics") Map<String,Object> own(@AuthenticationPrincipal Caller caller,@PathVariable String id) { return diagnostics.get(caller,id,false); }
    @GetMapping("/api/admin/tasks/{id}/diagnostics") Map<String,Object> admin(@AuthenticationPrincipal Caller caller,@PathVariable String id) { return diagnostics.get(caller,id,true); }
    @GetMapping("/api/admin/tasks") TaskService.Page<Map<String,Object>> recent(@AuthenticationPrincipal Caller caller,@RequestParam(defaultValue="0") int page) { return diagnostics.recent(caller,page); }
}
