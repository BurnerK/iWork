package io.iwork.identity;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

public class ApiFailure extends RuntimeException {
    final int status;

    public ApiFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<?> failure(ApiFailure error) {
        return ResponseEntity.status(error.status).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("error", "service_unavailable"));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_input"));
    }
}
