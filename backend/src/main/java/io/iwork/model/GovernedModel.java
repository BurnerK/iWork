package io.iwork.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.*;
import io.agentscope.core.model.transport.*;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.iwork.conversation.TaskRunner;
import io.iwork.identity.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class GovernedModel {
    public static final ExecutionConfig EXECUTION=ExecutionConfig.builder().maxAttempts(1).timeout(Duration.ofSeconds(120)).build();
    private final ModelCallLedger ledger;
    private final HttpTransport transport;
    private final Environment environment;
    private final ObjectMapper json;
    public GovernedModel(ModelCallLedger ledger,HttpTransport transport,Environment environment,ObjectMapper json) {
        this.ledger=ledger; this.transport=transport; this.environment=environment; this.json=json;
    }
    @Configuration(proxyBeanMethods=false)
    static class TransportConfiguration {
        @Bean HttpTransport providerTransport() { return JdkHttpTransport.builder().build(); }
    }
    public Model forTask(TaskRunner.Input input,String purpose) {
        return new Model() {
            public String getModelName() { return input.modelId(); }
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions ignored) {
                return Flux.defer(() -> {
                    if(tools!=null && tools.stream().anyMatch(tool -> !purpose.equals("MAIN") || !Set.of("read_task_material").contains(tool.getName()))) return Flux.error(new ApiFailure(403,"model_tools_not_authorized"));
                    int bytes=JsonUtils.getJsonCodec().toJson(Map.of("messages",messages,"tools",tools==null?List.of():tools)).getBytes(StandardCharsets.UTF_8).length;
                    var call=ledger.begin(input,purpose,bytes);
                    try {
                        var key=environment.getProperty(call.credentialRef());
                        if(key==null || key.isBlank()) throw new ApiFailure(503,"credential_not_configured");
                        var finished=new java.util.concurrent.atomic.AtomicBoolean();
                        var outputBytes=new java.util.concurrent.atomic.AtomicLong();
                        HttpTransport observed=new HttpTransport() {
                            public HttpResponse execute(HttpRequest request) { throw new IllegalStateException("Streaming required"); }
                            public Flux<String> stream(HttpRequest request) {
                                return Flux.defer(() -> {
                                    ledger.sent(call.id());
                                    return transport.stream(request).doOnNext(raw -> observe(call.id(),raw,finished,outputBytes))
                                        .doOnComplete(() -> { if(finished.get()) ledger.retain(() -> ledger.complete(call.id())); });
                                });
                            }
                            public void close() {}
                        };
                        var options=GenerateOptions.builder().maxTokens(ModelCallLedger.MAX_OUTPUT).executionConfig(EXECUTION)
                            .additionalBodyParam("stream_options",Map.of("include_usage",true)).build();
                        var upstream=OpenAIChatModel.builder().baseUrl(call.baseUrl()).modelName(call.modelName()).apiKey(key)
                            .stream(true).httpTransport(observed).generateOptions(options).build();
                        return upstream.stream(messages,tools,options)
                            .takeUntilOther(Mono.delay(Duration.ofSeconds(120)).flatMap(value -> Mono.error(new ApiFailure(504,"model_timeout"))))
                            .doFinally(signal -> ledger.retain(() -> ledger.finish(call.id())));
                    } catch(RuntimeException failure) { ledger.retain(() -> ledger.finish(call.id())); return Flux.error(failure); }
                });
            }
        };
    }
    private Long token(JsonNode usage,String field) {
        var value=usage.path(field);
        return value.isIntegralNumber() && value.canConvertToLong()?value.longValue():null;
    }
    private void observe(String id,String raw,java.util.concurrent.atomic.AtomicBoolean finished,java.util.concurrent.atomic.AtomicLong outputBytes) {
        if(raw.equals("[DONE]")) { ledger.retain(() -> ledger.complete(id)); return; }
        if(raw.length()>1048576) throw new ApiFailure(502,"model_output_limit");
        var chunk=json.readTree(raw);
        long output=0;
        for(var choice:chunk.path("choices")) {
            if(choice.hasNonNull("finish_reason")) finished.set(true);
            var delta=choice.path("delta");
            output+=delta.path("content").asText("").getBytes(StandardCharsets.UTF_8).length;
            output+=delta.path("reasoning_content").asText("").getBytes(StandardCharsets.UTF_8).length;
            if(delta.has("tool_calls")) output+=delta.get("tool_calls").toString().getBytes(StandardCharsets.UTF_8).length;
        }
        var usage=chunk.path("usage");
        long observed=outputBytes.addAndGet(output);
        if(!ledger.retain(() -> ledger.observe(id,observed,token(usage,"prompt_tokens"),token(usage,"completion_tokens"),chunk.path("id").asText(null))))
            throw new ApiFailure(503,"service_unavailable");
    }
}
