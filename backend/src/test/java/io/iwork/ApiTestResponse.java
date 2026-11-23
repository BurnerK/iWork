package io.iwork;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class ApiTestResponse {
    private static final JsonMapper JSON = new JsonMapper();

    private ApiTestResponse() {}

    public record RunResponse(int statusCode, String body) {}

    public static RunResponse submitRun(
            HttpClient client, int port, String threadId, String input, String csrf)
            throws Exception {
        var options = (ObjectNode) JSON.readTree(input);
        var runId = options.remove("requestId");
        var message = Map.of("id", runId, "role", "user", "content", options.remove("message"));
        var run =
                Map.of(
                        "threadId",
                        threadId,
                        "runId",
                        runId,
                        "messages",
                        List.of(message),
                        "state",
                        Map.of(),
                        "tools",
                        List.of(),
                        "context",
                        List.of(),
                        "forwardedProps",
                        options);
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:"
                                                + port
                                                + "/api/conversations/"
                                                + threadId
                                                + "/ag-ui"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "text/event-stream, application/json")
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrf)
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(run)))
                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofLines());
        try (var lines = response.body()) {
            if (response.statusCode() != 200) {
                return new RunResponse(
                        response.statusCode(),
                        lines.collect(java.util.stream.Collectors.joining("\n")));
            }
            var receipt =
                    lines.filter(line -> line.startsWith("data:"))
                            .map(line -> JSON.readTree(line.substring(5)))
                            .filter(
                                    event ->
                                            "CUSTOM".equals(event.path("type").asString())
                                                    && "iwork.task"
                                                            .equals(event.path("name").asString()))
                            .map(event -> event.path("value"))
                            .filter(value -> runId.equals(value.path("requestId")))
                            .findFirst()
                            .orElseThrow();
            return new RunResponse(response.statusCode(), receipt.toString());
        }
    }

    public static HttpResponse.BodyHandler<String> body() {
        return response ->
                HttpResponse.BodySubscribers.mapping(
                        HttpResponse.BodyHandlers.ofString().apply(response),
                        body -> {
                            if (response.statusCode() == 204
                                    || !response.headers()
                                            .firstValue("Content-Type")
                                            .orElse("")
                                            .startsWith("application/json")) return body;
                            var envelope = JSON.readTree(body);
                            boolean success = response.statusCode() < 400;
                            if (!envelope.path("success").isBoolean()
                                    || envelope.path("success").asBoolean() != success
                                    || !envelope.has("data")
                                    || !envelope.has("error")
                                    || (success
                                            ? !envelope.path("error").isNull()
                                            : !envelope.path("error").isString()
                                                    || !envelope.path("data").isNull())) {
                                throw new AssertionError(
                                        "Invalid API envelope for status " + response.statusCode());
                            }
                            return success ? envelope.path("data").toString() : body;
                        });
    }
}
