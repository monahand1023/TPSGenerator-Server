package io.kunkun.mockserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Component
@Validated
@ConfigurationProperties(prefix = "mock-server")
public class MockServerProperties {

    @Min(value = 0, message = "defaultMinDelay must be non-negative")
    private int defaultMinDelay = 10;

    @Min(value = 0, message = "defaultMaxDelay must be non-negative")
    private int defaultMaxDelay = 100;

    @Min(value = 0, message = "defaultErrorRate must be at least 0.0")
    @Max(value = 1, message = "defaultErrorRate must be at most 1.0")
    private double defaultErrorRate = 0.0;

    @Min(value = 1, message = "statsLogIntervalMs must be positive")
    private long statsLogIntervalMs = 10000;

    @Min(value = 1, message = "maxHistoryPerEndpoint must be at least 1")
    private int maxHistoryPerEndpoint = 100;

    @Min(value = 1, message = "maxEndpointConfigs must be at least 1")
    private int maxEndpointConfigs = 10000;

    private History history = new History();

    private Persistence persistence = new Persistence();

    private Proxy proxy = new Proxy();

    public Proxy getProxy() {
        return proxy;
    }

    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }

    public History getHistory() {
        return history;
    }

    public void setHistory(History history) {
        this.history = history;
    }

    public int getDefaultMinDelay() {
        return defaultMinDelay;
    }

    public void setDefaultMinDelay(int defaultMinDelay) {
        this.defaultMinDelay = defaultMinDelay;
    }

    public int getDefaultMaxDelay() {
        return defaultMaxDelay;
    }

    public void setDefaultMaxDelay(int defaultMaxDelay) {
        this.defaultMaxDelay = defaultMaxDelay;
    }

    public double getDefaultErrorRate() {
        return defaultErrorRate;
    }

    public void setDefaultErrorRate(double defaultErrorRate) {
        this.defaultErrorRate = defaultErrorRate;
    }

    public long getStatsLogIntervalMs() {
        return statsLogIntervalMs;
    }

    public void setStatsLogIntervalMs(long statsLogIntervalMs) {
        this.statsLogIntervalMs = statsLogIntervalMs;
    }

    public int getMaxHistoryPerEndpoint() {
        return maxHistoryPerEndpoint;
    }

    public void setMaxHistoryPerEndpoint(int maxHistoryPerEndpoint) {
        this.maxHistoryPerEndpoint = maxHistoryPerEndpoint;
    }

    public int getMaxEndpointConfigs() {
        return maxEndpointConfigs;
    }

    public void setMaxEndpointConfigs(int maxEndpointConfigs) {
        this.maxEndpointConfigs = maxEndpointConfigs;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    /**
     * Configuration for per-endpoint request history recording.
     */
    public static class History {
        /** Off by default — recording allocates a record (with a header copy) per request. */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Record/replay proxy: when enabled, requests to unconfigured paths are forwarded to an
     * upstream; the response is returned to the caller and (when {@code record} is true) captured
     * as a mock endpoint so subsequent requests replay it without hitting the upstream.
     */
    public static class Proxy {
        private boolean enabled = false;
        private String upstreamUrl;
        private boolean record = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUpstreamUrl() {
            return upstreamUrl;
        }

        public void setUpstreamUrl(String upstreamUrl) {
            this.upstreamUrl = upstreamUrl;
        }

        public boolean isRecord() {
            return record;
        }

        public void setRecord(boolean record) {
            this.record = record;
        }
    }

    /**
     * Configuration for endpoint configuration persistence.
     */
    public static class Persistence {
        private boolean enabled = false;
        private String filePath = "./mock-server-config.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }
    }
}
