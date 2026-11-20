package io.iwork.conversation;

import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.iwork.identity.ApiFailure;
import io.iwork.identity.Caller;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@RestController
class TaskController {
    private static final AguiEventEncoder AG_UI = new AguiEventEncoder();
    private static final Set<String> TASK_OPTIONS =
            Set.of("modelId", "agentId", "materialIds", "baseVersionId", "outputMode");
    private static final Set<String> SUBMISSION_FIELDS =
            Set.of("requestId", "message", "allowQueue");
    private final TaskService tasks;

    TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @PostMapping(
            value = "/api/conversations/{id}/ag-ui",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> run(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input,
            HttpServletResponse response) {
        if (!id.equals(input.get("threadId"))
                || !(input.get("runId") instanceof String runId)
                || !(input.get("messages") instanceof List<?> messages)
                || messages.size() != 1
                || !(messages.getFirst() instanceof Map<?, ?> message)
                || !"user".equals(message.get("role"))
                || !(message.get("id") instanceof String messageId)
                || messageId.isBlank()
                || !(message.get("content") instanceof String content)
                || !(input.get("forwardedProps") instanceof Map<?, ?> options)
                || !TASK_OPTIONS.containsAll(options.keySet()))
            throw new ApiFailure(400, "invalid_task");
        var submission = new HashMap<String, Object>();
        options.forEach((key, value) -> submission.put((String) key, value));
        submission.put("requestId", runId);
        submission.put("message", content);
        submission.put("allowQueue", true);
        return agUi(caller, submitTask(caller, id, submission), response);
    }

    @GetMapping(value = "/api/tasks/{id}/ag-ui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<String>> observe(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            HttpServletResponse response) {
        return agUi(caller, tasks.get(caller, id), response);
    }

    private Flux<ServerSentEvent<String>> agUi(
            Caller caller, TaskService.Task task, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        String threadId = task.conversationId(), runId = task.requestId();
        return Flux.defer(
                        () -> {
                            var reply = new AgUiReply(task);
                            var updates =
                                    observe(caller, task, reply)
                                            .repeatWhen(
                                                    completions ->
                                                            completions
                                                                    .takeWhile(
                                                                            ignored ->
                                                                                    !reply.finished)
                                                                    .delayElements(
                                                                            Duration.ofMillis(
                                                                                    500)));
                            return Flux.<AguiEvent>just(
                                            new AguiEvent.RunStarted(threadId, runId),
                                            taskEvent(task))
                                    .concatWith(updates)
                                    .onErrorResume(
                                            ApiFailure.class,
                                            error ->
                                                    Flux.just(
                                                            reply.observationError(
                                                                    error.getMessage())))
                                    .onErrorResume(
                                            DataAccessException.class,
                                            error ->
                                                    Flux.just(
                                                            reply.observationError(
                                                                    "service_unavailable")));
                        })
                .map(event -> ServerSentEvent.builder(AG_UI.encodeToJson(event)).build());
    }

    private AguiEvent taskEvent(TaskService.Task task) {
        return new AguiEvent.Custom(
                task.conversationId(),
                task.requestId(),
                "iwork.task",
                Map.of("id", task.id(), "requestId", task.requestId(), "status", task.status()));
    }

    private Flux<AguiEvent> observe(Caller caller, TaskService.Task task, AgUiReply reply) {
        return Flux.defer(
                        () -> {
                            var current = tasks.observedTask(caller, task.id());
                            Flux<AguiEvent> state =
                                    current.status().equals(reply.status)
                                            ? Flux.empty()
                                            : Flux.just(taskEvent(current));
                            reply.status = current.status();
                            if (current.status().equals("RUNNING")
                                    || current.status().equals("QUEUED")) {
                                return state.concatWith(
                                        tasks.replyStream(caller, current)
                                                .concatMapIterable(
                                                        snapshot ->
                                                                reply.update(snapshot.content())));
                            }
                            reply.finished = true;
                            return state.concatWith(
                                            Flux.fromIterable(
                                                    reply.update(tasks.savedReply(current))))
                                    .concatWith(Flux.fromIterable(reply.finish(current)));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static final class AgUiReply {
        private final String threadId;
        private final String runId;
        private final String messageId;
        private String content = "";
        private String status;
        private boolean finished;
        private boolean open;

        AgUiReply(TaskService.Task task) {
            this.threadId = task.conversationId();
            this.runId = task.requestId();
            this.messageId = task.id() + "-reply";
            this.status = task.status();
        }

        List<AguiEvent> update(String next) {
            if (next.equals(content)) return List.of();
            var events = new ArrayList<AguiEvent>();
            if (!next.startsWith(content)) {
                events.add(
                        new AguiEvent.MessagesSnapshot(
                                threadId,
                                runId,
                                List.of(AguiMessage.assistantMessage(messageId, next))));
            } else {
                if (!open) {
                    events.add(
                            new AguiEvent.TextMessageStart(
                                    threadId, runId, messageId, "assistant"));
                    open = true;
                }
                events.add(
                        new AguiEvent.TextMessageContent(
                                threadId, runId, messageId, next.substring(content.length())));
            }
            content = next;
            return events;
        }

        AguiEvent observationError(String code) {
            return new AguiEvent.Custom(
                    threadId, runId, "iwork.observation_error", Map.of("code", code));
        }

        List<AguiEvent> finish(TaskService.Task task) {
            String code =
                    task.errorCode() == null
                            ? task.status().toLowerCase(Locale.ROOT)
                            : task.errorCode();
            AguiEvent terminal =
                    task.status().equals("COMPLETED")
                            ? new AguiEvent.RunFinished(threadId, runId)
                            : new AguiEvent.RunError(threadId, runId, code, code);
            return open
                    ? List.of(new AguiEvent.TextMessageEnd(threadId, runId, messageId), terminal)
                    : List.of(terminal);
        }
    }

    @PostMapping("/api/conversations/{id}/tasks")
    @ResponseStatus(HttpStatus.ACCEPTED)
    TaskService.Task submit(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        return submitTask(caller, id, input);
    }

    private TaskService.Task submitTask(Caller caller, String id, Map<String, Object> input) {
        if (input.keySet().stream()
                        .anyMatch(
                                key ->
                                        !SUBMISSION_FIELDS.contains(key)
                                                && !TASK_OPTIONS.contains(key))
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

    @PostMapping("/api/tasks/{id}/pause")
    TaskService.Task pause(@AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return tasks.pause(caller, id);
    }

    @PostMapping("/api/tasks/{id}/continue")
    @ResponseStatus(HttpStatus.ACCEPTED)
    TaskService.Task continueReply(
            @AuthenticationPrincipal Caller caller, @PathVariable String id) {
        return tasks.continueReply(caller, id);
    }

    @GetMapping("/api/conversations/{id}/tasks")
    TaskService.TaskPage list(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) String requestId) {
        return tasks.list(caller, id, page, requestId);
    }

    @DeleteMapping("/api/conversations/{id}/tasks/queue/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeQueued(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @PathVariable String taskId) {
        tasks.removeQueued(caller, id, taskId);
    }

    @PostMapping("/api/conversations/{id}/tasks/queue/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resumeQueue(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestParam long pauseVersion) {
        tasks.resumeQueue(caller, id, pauseVersion);
    }

    @GetMapping("/api/conversations/{id}/messages")
    TaskService.Page<TaskService.Message> messages(
            @AuthenticationPrincipal Caller caller,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long after) {
        return tasks.messages(caller, id, after);
    }
}
