package io.iwork.identity;

public record ApiResponse<T>(boolean success, T data, String error) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> failure(String error) {
        return new ApiResponse<>(false, null, error);
    }
}
