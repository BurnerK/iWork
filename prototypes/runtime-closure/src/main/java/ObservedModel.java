import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import io.agentscope.core.model.transport.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Disposable observer: stores identifiers, timestamps and usage, never HTTP headers or prompts.
public final class ObservedModel implements Model {
    final RuntimeProbe probe;
    final String taskId;
    final String mode;
    final JsonNode config;
    ObservedModel(RuntimeProbe probe, String taskId, String mode) {
        this.probe = probe;
        this.taskId = taskId;
        this.mode = mode;
        this.config = probe.modelConfig;
    }

    public String getModelName() { return config.path("model").asText(); }

    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> schemas, GenerateOptions ignored) {
        return Flux.defer(() -> {
            String attempt = UUID.randomUUID().toString();
            probe.update(taskId, t -> {
                ObjectNode call = t.withObject("/attempts").putObject(attempt);
                call.put("sdkReplyId", t.path("activeSdkReplyId").asText());
                call.put("startedAt", System.currentTimeMillis()).putNull("providerUsage");
                call.putNull("adapterUsage");
            });
            HttpTransport transport = new HttpTransport() {
                final HttpTransport delegate = JdkHttpTransport.builder().build();
                public HttpResponse execute(HttpRequest request) {
                    throw new UnsupportedOperationException("Streaming only");
                }
                public Flux<String> stream(HttpRequest request) {
                    return Flux.defer(() -> {
                        if (probe.requestCount.incrementAndGet() > 16)
                            return Flux.error(new IllegalStateException("Prototype request limit reached"));
                        mark(attempt, c -> c.put("httpStartedAt", System.currentTimeMillis()));
                        return delegate.stream(request).handle((chunk, sink) -> {
                            try {
                                if (!chunk.equals("[DONE]")) {
                                    JsonNode raw = RuntimeProbe.JSON.readTree(chunk);
                                    boolean content = false;
                                    for (JsonNode choice : raw.path("choices")) {
                                        JsonNode delta = choice.path("delta");
                                        content |= !delta.path("content").asText("").isEmpty()
                                            || !delta.path("reasoning_content").asText("").isEmpty()
                                            || delta.path("tool_calls").size() > 0;
                                    }
                                    boolean hasContent = content;
                                    mark(attempt, c -> {
                                        if (!c.has("firstChunkAt")) c.put("firstChunkAt", System.currentTimeMillis());
                                        if (raw.hasNonNull("id")) c.put("providerResponseId", raw.path("id").asText());
                                        if (raw.hasNonNull("usage")) c.set("providerUsage", raw.get("usage"));
                                        if (hasContent && !c.has("firstContentAt")) c.put("firstContentAt", System.currentTimeMillis());
                                    });
                                    if (content && mode.equals("stream-fail")) {
                                        mark(attempt, c -> c.put("injectedClientFailure", true));
                                        sink.error(new IllegalStateException("Injected client stream failure"));
                                        return;
                                    }
                                }
                                sink.next(chunk);
                            } catch (Exception error) {
                                sink.error(new IllegalStateException("Stream observation failed"));
                            }
                        }).cast(String.class)
                        .doOnError(error -> mark(attempt, c -> {
                            c.put("transportErrorClass", error.getClass().getSimpleName());
                            if (error instanceof HttpTransportException http && http.getStatusCode() != null)
                                c.put("httpErrorStatus", http.getStatusCode());
                        }))
                        .doFinally(signal -> mark(attempt, c -> c.put("transportSignal", signal.name())
                                .put("transportEndedAt", System.currentTimeMillis())));
                    });
                }
                public void close() { delegate.close(); }
            };
            Model upstream = OpenAIChatModel.builder().apiKey(config.path("apiKey").asText())
                .baseUrl(config.path("baseUrl").asText()).modelName(getModelName())
                .stream(true).httpTransport(transport)
                .generateOptions(GenerateOptions.builder().maxTokens(512)
                    .additionalBodyParam("thinking", Map.of("type", "disabled")).build()).build();
            return upstream.stream(messages, schemas, null)
                .doOnNext(chunk -> {
                    if (chunk.getUsage() != null) {
                        ChatUsage usage = chunk.getUsage();
                        mark(attempt, c -> c.set("adapterUsage", RuntimeProbe.JSON.valueToTree(usage)));
                    }
                })
                .doFinally(signal -> {
                    mark(attempt, c -> c.put("modelSignal", signal.name()).put("modelEndedAt", System.currentTimeMillis()));
                    transport.close();
                });
        });
    }

    void mark(String attempt, java.util.function.Consumer<ObjectNode> change) {
        probe.update(taskId, t -> change.accept((ObjectNode)t.path("attempts").path(attempt)));
    }
}
