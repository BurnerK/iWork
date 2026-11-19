package io.iwork.conversation;

import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
class TaskController {
    private final TaskService tasks;

    TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @PostMapping("/api/conversations/{id}/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    TaskService.Task submit(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        if (!Set.of(
                                "requestId",
                                "modelId",
                                "message",
                                "materialIds",
                                "outputMode",
                                "baseVersionId",
                                "agentId",
                                "allowQueue")
                        .containsAll(input.keySet())
                || !(input.get("requestId") instanceof String requestId)
                || !(input.get("message") instanceof String message))
            throw new ApiFailure(400, "invalid_task");
        Object raw = input.getOrDefault("materialIds", List.of());
        if (!(raw instanceof List<?> ids)
                || ids.stream().anyMatch(value -> !(value instanceof String)))
            throw new ApiFailure(400, "invalid_task");
        if (!(input.getOrDefault("outputMode", "TEXT") instanceof String outputMode))
            throw new ApiFailure(400, "invalid_task");
        if (!(input.getOrDefault("allowQueue", false) instanceof Boolean allowQueue))
            throw new ApiFailure(400, "invalid_task");
        Object base = input.get("baseVersionId"),
                agent = input.get("agentId"),
                model = input.get("modelId");
        if (agent != null && !(agent instanceof String)
                || model != null && !(model instanceof String))
            throw new ApiFailure(400, "invalid_task");
        if (base != null && !(base instanceof String)) throw new ApiFailure(400, "invalid_task");
        return tasks.submit(
                caller,
                id,
                requestId,
                (String) model,
                message,
                ids.stream().map(String.class::cast).toList(),
                outputMode,
                (String) base,
                (String) agent,
                allowQueue);
    }

    @GetMapping("/api/tasks/{id}/materials")
    List<io.iwork.artifacts.MaterialService.Material> materials(
            @AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return tasks.materials(caller, id);
    }

    @GetMapping("/api/tasks/{id}")
    TaskService.Task get(@AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return tasks.get(caller, id);
    }

    @GetMapping(
            value = "/api/tasks/{id}/reply-stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<TaskService.ReplySnapshot>> replyStream(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            HttpServletResponse response) {
        var snapshots = tasks.replyStream(caller, id);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return snapshots
                .map(snapshot -> ServerSentEvent.builder(snapshot).event("reply").build())
                .concatWithValues(
                        ServerSentEvent.<TaskService.ReplySnapshot>builder()
                                .event("done")
                                .data(new TaskService.ReplySnapshot(0, ""))
                                .build());
    }

    @PostMapping("/api/tasks/{id}/cancel")
    TaskService.Task cancel(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        if (!Set.of("pauseQueue").containsAll(input.keySet())
                || !(input.getOrDefault("pauseQueue", false) instanceof Boolean pauseQueue))
            throw new ApiFailure(400, "invalid_input");
        return tasks.cancel(caller, id, pauseQueue);
    }

    @GetMapping("/api/conversations/{id}/tasks")
    TaskService.TaskPage list(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String requestId) {
        return tasks.list(caller, id, page, requestId);
    }

    @GetMapping("/api/conversations/{id}/messages")
    TaskService.Page<TaskService.Message> messages(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long after) {
        return tasks.messages(caller, id, after);
    }
}
