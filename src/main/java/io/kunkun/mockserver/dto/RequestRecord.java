package io.kunkun.mockserver.dto;

import java.util.Collections;
import java.util.Map;

/**
 * Immutable record of a single HTTP request handled by the mock server.
 * No setters — all fields are set at construction time only.
 */
public class RequestRecord {

    private final long timestamp;
    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final String requestBody;
    private final int responseStatus;
    private final long processingTimeMs;

    public RequestRecord(
            long timestamp,
            String method,
            String path,
            Map<String, String> headers,
            String requestBody,
            int responseStatus,
            long processingTimeMs) {
        this.timestamp = timestamp;
        this.method = method;
        this.path = path;
        // Defensive copy so the caller's map cannot mutate this record
        this.headers = Collections.unmodifiableMap(Map.copyOf(headers));
        this.requestBody = requestBody;
        this.responseStatus = responseStatus;
        this.processingTimeMs = processingTimeMs;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }
}
