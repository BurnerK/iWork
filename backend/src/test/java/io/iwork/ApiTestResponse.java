package io.iwork;

import java.net.http.HttpResponse;
import tools.jackson.databind.json.JsonMapper;

public final class ApiTestResponse {
    private static final JsonMapper JSON = new JsonMapper();

    private ApiTestResponse() {}

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
