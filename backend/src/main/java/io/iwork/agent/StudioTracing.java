package io.iwork.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iwork.studio.enabled", havingValue = "true")
class StudioTracing {
    @Bean(destroyMethod = "close")
    SdkTracerProvider studioTracerProvider() {
        var exporter =
                OtlpHttpSpanExporter.builder()
                        .setEndpoint("http://127.0.0.1:13000/v1/traces")
                        .setTimeout(Duration.ofSeconds(2))
                        .build();
        return SdkTracerProvider.builder()
                .setResource(
                        Resource.create(
                                Attributes.of(AttributeKey.stringKey("service.name"), "iwork-dev")))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();
    }

    @Bean
    Tracer studioTracer(SdkTracerProvider studioTracerProvider) {
        return studioTracerProvider.get("iwork.harness");
    }

    // Keep a private tracer so JDBC and other globally instrumented libraries cannot export here.
    record Middleware(Tracer tracer, String taskId) implements MiddlewareBase {
        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent,
                RuntimeContext context,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            return trace(
                    () ->
                            tracer.spanBuilder("invoke_agent " + agent.getName())
                                    .setAttribute("gen_ai.operation.name", "invoke_agent")
                                    .setAttribute("gen_ai.agent.name", agent.getName())
                                    .setAttribute("gen_ai.agent.id", taskId),
                    ignored -> next.apply(input));
        }

        @Override
        public Flux<AgentEvent> onModelCall(
                Agent agent,
                RuntimeContext context,
                ModelCallInput input,
                Function<ModelCallInput, Flux<AgentEvent>> next) {
            return trace(
                    () ->
                            tracer.spanBuilder("chat " + input.model().getModelName())
                                    .setAttribute("gen_ai.operation.name", "chat")
                                    .setAttribute(
                                            "gen_ai.request.model", input.model().getModelName()),
                    span ->
                            next.apply(input)
                                    .doOnNext(
                                            event -> {
                                                if (event instanceof ModelCallEndEvent end
                                                        && end.getUsage() != null) {
                                                    span.setAttribute(
                                                            "gen_ai.usage.input_tokens",
                                                            end.getUsage().getInputTokens());
                                                    span.setAttribute(
                                                            "gen_ai.usage.output_tokens",
                                                            end.getUsage().getOutputTokens());
                                                }
                                            }));
        }

        @Override
        public Flux<AgentEvent> onActing(
                Agent agent,
                RuntimeContext context,
                ActingInput input,
                Function<ActingInput, Flux<AgentEvent>> next) {
            var knownNames = agent.getToolkit().getToolNames();
            var names =
                    input.toolCalls().stream()
                            .map(ToolUseBlock::getName)
                            .filter(knownNames::contains)
                            .distinct()
                            .toList();
            return trace(
                    () ->
                            tracer.spanBuilder("execute_tool " + String.join(", ", names))
                                    .setAttribute("gen_ai.operation.name", "execute_tool")
                                    .setAttribute("gen_ai.tool.name", String.join(", ", names)),
                    ignored -> next.apply(input));
        }

        private Flux<AgentEvent> trace(
                Supplier<SpanBuilder> builder, Function<Span, Flux<AgentEvent>> source) {
            return Flux.deferContextual(
                    context -> {
                        Context parent = context.getOrDefault(StudioTracing.class, Context.root());
                        Span span = builder.get().setParent(parent).startSpan();
                        var resultDelivered = new AtomicBoolean();
                        return Flux.defer(() -> source.apply(span))
                                .doOnNext(
                                        event -> {
                                            // Agent.call() cancels the event subscription after it
                                            // receives the result.
                                            if (event instanceof AgentResultEvent)
                                                resultDelivered.set(true);
                                        })
                                .doFinally(
                                        signal -> {
                                            span.setStatus(
                                                    (signal == SignalType.ON_COMPLETE
                                                                    || (signal == SignalType.CANCEL
                                                                            && resultDelivered
                                                                                    .get()))
                                                            ? StatusCode.OK
                                                            : StatusCode.ERROR);
                                            span.end();
                                        })
                                .contextWrite(
                                        current ->
                                                current.put(
                                                        StudioTracing.class, parent.with(span)));
                    });
        }
    }
}
