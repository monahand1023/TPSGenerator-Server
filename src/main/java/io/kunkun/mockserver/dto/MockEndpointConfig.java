package io.kunkun.mockserver.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.util.HashMap;
import java.util.Map;

public class MockEndpointConfig {

    @Min(value = 0, message = "minDelay must be non-negative")
    private int minDelay;

    @Min(value = 0, message = "maxDelay must be non-negative")
    private int maxDelay;

    @Min(value = 0, message = "errorRate must be at least 0.0")
    @Max(value = 1, message = "errorRate must be at most 1.0")
    private double errorRate;

    private Map<String, Object> responseHeaders = new HashMap<>();

    private String responseMessage;

    public MockEndpointConfig() {
    }

    public MockEndpointConfig(int minDelay, int maxDelay, double errorRate,
                              Map<String, Object> responseHeaders, String responseMessage) {
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.errorRate = errorRate;
        this.responseHeaders = responseHeaders != null ? responseHeaders : new HashMap<>();
        this.responseMessage = responseMessage;
    }

    /**
     * Validates the configuration values.
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (minDelay < 0) {
            throw new IllegalArgumentException("minDelay must be non-negative");
        }
        if (maxDelay < 0) {
            throw new IllegalArgumentException("maxDelay must be non-negative");
        }
        if (minDelay > maxDelay) {
            throw new IllegalArgumentException("minDelay cannot exceed maxDelay");
        }
        if (errorRate < 0.0 || errorRate > 1.0) {
            throw new IllegalArgumentException("errorRate must be between 0.0 and 1.0");
        }
    }

    public int getMinDelay() {
        return minDelay;
    }

    public void setMinDelay(int minDelay) {
        if (minDelay < 0) {
            throw new IllegalArgumentException("minDelay must be non-negative");
        }
        this.minDelay = minDelay;
    }

    public int getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(int maxDelay) {
        if (maxDelay < 0) {
            throw new IllegalArgumentException("maxDelay must be non-negative");
        }
        this.maxDelay = maxDelay;
    }

    public double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
        if (errorRate < 0.0 || errorRate > 1.0) {
            throw new IllegalArgumentException("errorRate must be between 0.0 and 1.0");
        }
        this.errorRate = errorRate;
    }

    public Map<String, Object> getResponseHeaders() {
        return responseHeaders != null ? responseHeaders : new HashMap<>();
    }

    public void setResponseHeaders(Map<String, Object> responseHeaders) {
        this.responseHeaders = responseHeaders != null ? responseHeaders : new HashMap<>();
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public void setResponseMessage(String responseMessage) {
        this.responseMessage = responseMessage;
    }
}
