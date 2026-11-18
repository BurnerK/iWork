package io.iwork.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

public class ApiFailure extends RuntimeException {
    final int status;

    public ApiFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    static void write(
            HttpServletRequest request, HttpServletResponse response, int status, String code)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        String prefix =
                request.getRequestURI().startsWith("/api/")
                        ? "{\"success\":false,\"data\":null,"
                        : "{";
        response.getWriter().write(prefix + "\"error\":\"" + code + "\"}");
    }
}

@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> failure(ApiFailure error) {
        return ResponseEntity.status(error.status)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure(error.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(ApiResponse.failure("service_unavailable"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception error,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String code =
                switch (status.value()) {
                    case 404 -> "not_found";
                    case 405 -> "method_not_allowed";
                    case 415 -> "unsupported_media_type";
                    default -> status.is5xxServerError() ? "service_unavailable" : "invalid_input";
                };
        if (request instanceof org.springframework.web.context.request.ServletWebRequest servlet
                && servlet.getRequest().getRequestURI().startsWith("/api/")) {
            var responseHeaders = new HttpHeaders();
            responseHeaders.putAll(headers);
            responseHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            return super.handleExceptionInternal(
                    error, ApiResponse.failure(code), responseHeaders, status, request);
        }
        return super.handleExceptionInternal(error, body, headers, status, request);
    }
}
