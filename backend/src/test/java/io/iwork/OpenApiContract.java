package io.iwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class OpenApiContract {
    private static final String JSON = "application/json";
    private static final String LOGOUT = "/api/auth/logout";
    private static final Map<String, String> TAGS =
            Map.ofEntries(
                    Map.entry("auth-controller", "身份认证"),
                    Map.entry("admin-controller", "用户管理"),
                    Map.entry("conversation-controller", "会话"),
                    Map.entry("task-controller", "会话任务"),
                    Map.entry("task-diagnostics-controller", "任务诊断"),
                    Map.entry("project-controller", "项目空间"),
                    Map.entry("personal-agent-controller", "个人智能体"),
                    Map.entry("material-controller", "资料"),
                    Map.entry("document-controller", "文档成果"),
                    Map.entry("parsing-controller", "资料解析"),
                    Map.entry("model-controller", "模型与预算"),
                    Map.entry("model-usage-controller", "模型调用记录"));

    private OpenApiContract() {}

    static void verify(WebApplicationContext web) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web).build();
        var raw =
                mvc.perform(get("/v3/api-docs.yaml"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        var mapper = Yaml.mapper().copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var api = mapper.readValue(raw, OpenAPI.class);
        try (var input = OpenApiContract.class.getResourceAsStream("/openapi-details.yaml")) {
            var yaml = new org.yaml.snakeyaml.Yaml(new SafeConstructor(new LoaderOptions()));
            var details =
                    mapper.convertValue(yaml.load(Objects.requireNonNull(input)), OpenAPI.class);
            details.getComponents().getSchemas().forEach(api.getComponents()::addSchemas);
            details.getComponents().getResponses().forEach(api.getComponents()::addResponses);
            details.getPaths().forEach((path, item) -> supplement(api, path, item));
        }

        api.info(
                        new Info()
                                .title("iWork API")
                                .version("0.1.0")
                                .description("写请求先调用 GET /api/auth/csrf，保留会话 Cookie，并在请求头传入返回的令牌。"))
                .servers(List.of(new Server().url("http://127.0.0.1:8080").description("本地开发")));
        api.setTags(null);
        api.getComponents()
                .addSecuritySchemes(
                        "session",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("IWORK_SESSION"))
                .addSecuritySchemes(
                        "csrf",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-CSRF-TOKEN"));
        api.getPaths()
                .forEach(
                        (path, item) ->
                                item.readOperationsMap()
                                        .forEach(
                                                (method, operation) ->
                                                        complete(path, method, operation)));

        var output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(api);
        var options = new ParseOptions();
        options.setResolve(true);
        var parsed = new OpenAPIV3Parser().readContents(output, null, options);
        assertThat(parsed.getMessages()).as("OpenAPI validation messages").isEmpty();
        assertThat(parsed.getOpenAPI().getPaths()).isNotEmpty();

        var contract = Path.of("..", "openapi.yaml");
        if (Boolean.getBoolean("iwork.openapi.update")) {
            var generated = Files.createTempFile(contract.getParent(), ".openapi-", ".yaml");
            try {
                Files.writeString(generated, output, StandardCharsets.UTF_8);
                Files.move(generated, contract, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(generated);
            }
        } else {
            assertThat(mapper.readTree(Files.readString(contract, StandardCharsets.UTF_8)))
                    .as("openapi.yaml is current (regenerate with -Diwork.openapi.update=true)")
                    .isEqualTo(mapper.readTree(output));
        }
    }

    private static void supplement(OpenAPI api, String path, PathItem details) {
        // Spring Security handles logout before MVC dispatch.
        if (LOGOUT.equals(path)) {
            details.getPost().addTagsItem("auth-controller");
            api.path(path, details);
            return;
        }
        var generated = api.getPaths().get(path);
        assertThat(generated).as(path).isNotNull();
        details.readOperationsMap()
                .forEach(
                        (method, overlay) -> {
                            var operation = generated.readOperationsMap().get(method);
                            assertThat(operation).as("%s %s", method, path).isNotNull();
                            operation
                                    .summary(overlay.getSummary())
                                    .description(overlay.getDescription());
                            if (overlay.getRequestBody() != null)
                                operation.requestBody(overlay.getRequestBody());
                            if (overlay.getResponses() != null)
                                operation.responses(overlay.getResponses());
                            if (path.equals("/api/auth/login") || path.equals("/api/auth/csrf")) {
                                operation.setParameters(null);
                            }
                        });
    }

    private static void complete(String path, PathItem.HttpMethod method, Operation operation) {
        var tag = TAGS.get(operation.getTags().getFirst());
        assertThat(tag).as("business tag for %s %s", method, path).isNotNull();
        var operationId =
                method.name().toLowerCase(Locale.ROOT) + path.replaceAll("[^a-zA-Z0-9]+", "_");
        operation.tags(List.of(tag)).operationId(operationId.replaceAll("_$", ""));
        if (operation.getSummary() == null) operation.summary(method + " " + path);
        boolean publicRead = path.equals("/api/auth/csrf");
        boolean write = method != PathItem.HttpMethod.GET;
        if (!publicRead) {
            var security = new SecurityRequirement().addList("session");
            if (write) security.addList("csrf");
            operation.addSecurityItem(security);
        }
        if (operation.getRequestBody() != null) {
            operation
                    .getRequestBody()
                    .getContent()
                    .forEach(
                            (media, content) -> {
                                var schema = content.getSchema();
                                assertThat(schema).as("body of %s %s", method, path).isNotNull();
                                if (JSON.equals(media) || "*/*".equals(media)) {
                                    assertThat(
                                                    schema.get$ref() != null
                                                            || schema.getProperties() != null)
                                            .as(
                                                    "typed body for %s %s (see openapi-details.yaml)",
                                                    method, path)
                                            .isTrue();
                                }
                            });
            normalizeJson(operation.getRequestBody().getContent());
        }
        operation
                .getResponses()
                .forEach(
                        (code, response) -> {
                            if ("204".equals(code)) response.setContent(null);
                            if (response.getContent() == null) return;
                            normalizeJson(response.getContent());
                            var json = response.getContent().get(JSON);
                            if (json == null) return;
                            var envelope = new ObjectSchema();
                            envelope.addProperty(
                                            "success", new BooleanSchema()._enum(List.of(true)))
                                    .addProperty("data", json.getSchema())
                                    .addProperty("error", new StringSchema().nullable(true));
                            envelope.setRequired(List.of("success", "data", "error"));
                            json.setSchema(envelope);
                        });
        operation
                .getResponses()
                .putIfAbsent("default", new ApiResponse().$ref("#/components/responses/ApiError"));
    }

    private static void normalizeJson(Content content) {
        MediaType fallback = content.remove("*/*");
        if (fallback != null) content.addMediaType(JSON, fallback);
    }
}
