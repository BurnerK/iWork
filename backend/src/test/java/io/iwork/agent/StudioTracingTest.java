package io.iwork.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class StudioTracingTest {
    @Test
    void tracesTaskResultsAndFailuresWithoutExceptionPayload() {
        var exported = new ArrayList<SpanData>();
        var exporter = mock(SpanExporter.class);
        when(exporter.export(any()))
                .thenAnswer(
                        invocation -> {
                            exported.addAll(invocation.<Collection<SpanData>>getArgument(0));
                            return CompletableResultCode.ofSuccess();
                        });
        when(exporter.shutdown()).thenReturn(CompletableResultCode.ofSuccess());

        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("iWork");
        when(agent.getAgentId()).thenReturn("harness-internal-id");
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("model-id");
        var context = RuntimeContext.builder().build();
        var input = new ModelCallInput(List.of(), List.of(), null, model);
        var failure = new IllegalStateException("private provider response");
        try (var provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            var middleware = new StudioTracing.Middleware(provider.get("test"), "task-id");
            assertThatThrownBy(
                            () ->
                                    middleware
                                            .onAgent(
                                                    agent,
                                                    context,
                                                    new AgentInput(List.of()),
                                                    ignored ->
                                                            middleware.onModelCall(
                                                                    agent,
                                                                    context,
                                                                    input,
                                                                    call -> Flux.error(failure)))
                                            .blockLast())
                    .isSameAs(failure);

            var result =
                    new AgentResultEvent(
                            Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build());
            assertThat(
                            middleware
                                    .onAgent(
                                            agent,
                                            context,
                                            new AgentInput(List.of()),
                                            ignored ->
                                                    Flux.<AgentEvent>just(result)
                                                            .concatWith(Flux.never()))
                                    .next()
                                    .block())
                    .isSameAs(result);
        }

        assertThat(exported).hasSize(3);
        var task =
                exported.stream()
                        .filter(span -> span.getName().equals("invoke_agent iWork"))
                        .findFirst()
                        .orElseThrow();
        var call =
                exported.stream()
                        .filter(span -> span.getName().equals("chat model-id"))
                        .findFirst()
                        .orElseThrow();
        assertThat(task.getAttributes().asMap()).containsValue("task-id");
        assertThat(call.getParentSpanId()).isEqualTo(task.getSpanId());
        assertThat(exported.subList(0, 2))
                .allSatisfy(
                        span -> {
                            assertThat(span.getStatus().getStatusCode())
                                    .isEqualTo(StatusCode.ERROR);
                            assertThat(span.getStatus().getDescription()).isEmpty();
                            assertThat(span.getEvents()).isEmpty();
                        });
        assertThat(exported.getLast().getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }
}
