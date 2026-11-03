package br.com.quack.pdv.shared;

/** Envelope padrao de resposta (equivale ao ApiResponse de T da API .NET). */
public record ApiResponse<T>(boolean success, String message, String details, T data) {

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, null, data);
    }

    public static <T> ApiResponse<T> ok(String message) {
        return new ApiResponse<>(true, message, null, null);
    }

    public static <T> ApiResponse<T> erro(String message) {
        return new ApiResponse<>(false, message, null, null);
    }

    public static <T> ApiResponse<T> erro(String message, String details) {
        return new ApiResponse<>(false, message, details, null);
    }

    public static <T> ApiResponse<T> erro(String message, String details, T data) {
        return new ApiResponse<>(false, message, details, data);
    }
}
