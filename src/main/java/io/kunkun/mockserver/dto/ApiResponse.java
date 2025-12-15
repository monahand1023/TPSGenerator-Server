package io.kunkun.mockserver.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * Fluent builder for standardized API responses.
 * Eliminates duplicate response building code across controllers.
 */
public class ApiResponse {

    private final Map<String, Object> data = new HashMap<>();

    private ApiResponse() {
    }

    public static ApiResponse success() {
        return new ApiResponse().with("status", "success");
    }

    public static ApiResponse error() {
        return new ApiResponse().with("status", "error");
    }

    public ApiResponse with(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public ApiResponse withMessage(String message) {
        return with("message", message);
    }

    public ApiResponse withRequestId(long requestId) {
        return with("requestId", requestId);
    }

    public ApiResponse withProcessingTime(int processingTime) {
        return with("processingTime", processingTime);
    }

    public Map<String, Object> build() {
        return new HashMap<>(data);
    }
}
