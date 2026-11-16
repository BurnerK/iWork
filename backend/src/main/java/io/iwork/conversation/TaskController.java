package io.iwork.conversation;

import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
class TaskController {
    private final TaskService tasks;
    TaskController(TaskService tasks) { this.tasks=tasks; }
    @PostMapping("/api/conversations/{id}/tasks") @ResponseStatus(HttpStatus.ACCEPTED)
    TaskService.Task submit(@AuthenticationPrincipal Caller caller,@PathVariable String id,@RequestBody Map<String,Object> input) {
        if(!input.keySet().equals(Set.of("requestId","modelId","message")) || !(input.get("requestId") instanceof String requestId)
            || !(input.get("modelId") instanceof String modelId) || !(input.get("message") instanceof String message)) throw new ApiFailure(400,"invalid_task");
        return tasks.submit(caller,id,requestId,modelId,message);
    }
    @GetMapping("/api/tasks/{id}") TaskService.Task get(@AuthenticationPrincipal Caller caller,@PathVariable String id) { return tasks.get(caller,id); }
    @PostMapping("/api/tasks/{id}/cancel") TaskService.Task cancel(@AuthenticationPrincipal Caller caller,@PathVariable String id,@RequestBody Map<String,Object> input) {
        if(!input.isEmpty()) throw new ApiFailure(400,"invalid_input");
        return tasks.cancel(caller,id);
    }
    @GetMapping("/api/conversations/{id}/tasks") TaskService.Page<TaskService.Task> list(@AuthenticationPrincipal Caller caller,@PathVariable String id,@RequestParam(defaultValue="0") int page) { return tasks.list(caller,id,page); }
    @GetMapping("/api/conversations/{id}/messages") TaskService.Page<TaskService.Message> messages(@AuthenticationPrincipal Caller caller,@PathVariable String id,@RequestParam(defaultValue="0") long after) { return tasks.messages(caller,id,after); }
}
