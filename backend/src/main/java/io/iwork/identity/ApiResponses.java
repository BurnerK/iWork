package io.iwork.identity;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
class ApiResponses implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(
            MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return !method.getParameterType().equals(void.class);
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter method,
            MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converter,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!request.getURI().getPath().startsWith("/api/")
                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || body instanceof ApiResponse<?>) return body;
        return ApiResponse.ok(body);
    }
}
