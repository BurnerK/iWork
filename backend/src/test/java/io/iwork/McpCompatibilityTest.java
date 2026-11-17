package io.iwork;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.util.JsonUtils;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpCompatibilityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void agentScopeCanInitializeListAndCallTools(boolean async) throws Exception {
        var codec = JsonUtils.getJsonCodec();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/mcp",
                exchange -> {
                    try (exchange) {
                        if (!exchange.getRequestMethod().equals("POST")) {
                            exchange.sendResponseHeaders(405, -1);
                            return;
                        }
                        var request =
                                codec.fromJson(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                java.nio.charset.StandardCharsets.UTF_8),
                                        Map.class);
                        if (!request.containsKey("id")) {
                            exchange.sendResponseHeaders(202, -1);
                            return;
                        }
                        Object result =
                                switch ((String) request.get("method")) {
                                    case "initialize" ->
                                            Map.of(
                                                    "protocolVersion",
                                                    "2025-06-18",
                                                    "serverInfo",
                                                    Map.of("name", "fixture", "version", "1"),
                                                    "capabilities",
                                                    Map.of("tools", Map.of()));
                                    case "tools/list" ->
                                            Map.of(
                                                    "tools",
                                                    java.util.List.of(
                                                            Map.of(
                                                                    "name",
                                                                    "echo",
                                                                    "description",
                                                                    "Echo input",
                                                                    "inputSchema",
                                                                    Map.of(
                                                                            "type",
                                                                            "object",
                                                                            "properties",
                                                                            Map.of(
                                                                                    "text",
                                                                                    Map.of(
                                                                                            "type",
                                                                                            "string"))))));
                                    case "tools/call" -> {
                                        var params = (Map<?, ?>) request.get("params");
                                        var args = (Map<?, ?>) params.get("arguments");
                                        yield Map.of(
                                                "content",
                                                java.util.List.of(
                                                        Map.of(
                                                                "type",
                                                                "text",
                                                                "text",
                                                                args.get("text"))),
                                                "isError",
                                                false);
                                    }
                                    default ->
                                            throw new IllegalArgumentException(
                                                    "Unexpected method: " + request.get("method"));
                                };
                        var response =
                                codec.toJson(
                                                Map.of(
                                                        "jsonrpc",
                                                        "2.0",
                                                        "id",
                                                        request.get("id"),
                                                        "result",
                                                        result))
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                    }
                });
        server.start();
        try {
            var timeout = Duration.ofSeconds(5);
            var builder =
                    McpClientBuilder.create("compatibility")
                            .streamableHttpTransport(
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp")
                            .initializationTimeout(timeout)
                            .timeout(timeout);
            try (var client = async ? builder.buildAsync().block(timeout) : builder.buildSync()) {
                client.initialize().block(timeout);
                assertThat(client.listTools().block(timeout))
                        .extracting("name")
                        .containsExactly("echo");
                var result = client.callTool("echo", Map.of("text", "兼容性检查")).block(timeout);
                assertThat(result.isError()).isFalse();
                assertThat(((TextContent) result.content().getFirst()).text()).isEqualTo("兼容性检查");
            }
        } finally {
            server.stop(0);
        }
    }
}
