package io.kunkun.mockserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a mock endpoint.
 *
 * Validation uses JSR-380 (Bean Validation) annotations throughout:
 * - Field-level constraints (@Min, @Max) catch simple range violations
 * - @AssertTrue covers the cross-field constraint (minDelay <= maxDelay)
 * - The controller boundary has @Valid + @Validated so all constraints are
 *   enforced before any service method is called
 *
 * The manual validate() method and setter guards have been removed; they
 * duplicated the same logic in a less composable, harder-to-test way. The
 * remaining validate() call in MockEndpointService.configureEndpoint() is
 * kept as a defence-in-depth check for programmatic (non-HTTP) callers.
 */
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

    /**
     * Optional weighted distribution of HTTP status codes, keyed by status code as a string
     * (e.g. {@code {"200": 70, "429": 20, "503": 10}}). When non-empty it takes precedence over
     * {@link #errorRate}: a status is drawn by weight, and any 2xx counts as success.
     */
    private Map<String, Integer> statusDistribution;

    /**
     * Delay distribution between {@link #minDelay} and {@link #maxDelay}: {@code uniform} (default),
     * {@code normal}, or {@code lognormal}. Real latency is long-tailed, so {@code lognormal}
     * produces more realistic p99s than a flat uniform draw.
     */
    private String delayDistribution = "uniform";

    /**
     * Optional raw response body returned for successful responses, replacing the default JSON
     * envelope. Supports {@code ${requestId}}, {@code ${timestamp}}, and {@code ${random}}
     * placeholders. Set a {@code Content-Type} in {@link #responseHeaders} to match.
     */
    private String responseBody;

    /**
     * Optional minimum response-body size in bytes. When &gt; 0 the body is padded with filler so
     * it is at least this large — useful for stressing client-side deserialization and bandwidth.
     */
    private int responseSizeBytes = 0;

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
     * Cross-field constraint: minDelay must not exceed maxDelay.
     * The @JsonIgnore prevents this synthetic property appearing in serialized output.
     */
    @jakarta.validation.constraints.AssertTrue(message = "minDelay cannot exceed maxDelay")
    @JsonIgnore
    public boolean isDelayRangeValid() {
        return minDelay <= maxDelay;
    }

    /**
     * Validates this config programmatically (for callers that bypass the HTTP/Bean Validation layer).
     * Throws IllegalArgumentException on the first violation found.
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
        this.minDelay = minDelay;
    }

    public int getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(int maxDelay) {
        this.maxDelay = maxDelay;
    }

    public double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
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

    public Map<String, Integer> getStatusDistribution() {
        return statusDistribution;
    }

    public void setStatusDistribution(Map<String, Integer> statusDistribution) {
        this.statusDistribution = statusDistribution;
    }

    public String getDelayDistribution() {
        return delayDistribution;
    }

    public void setDelayDistribution(String delayDistribution) {
        this.delayDistribution = delayDistribution;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public int getResponseSizeBytes() {
        return responseSizeBytes;
    }

    public void setResponseSizeBytes(int responseSizeBytes) {
        this.responseSizeBytes = responseSizeBytes;
    }
}
