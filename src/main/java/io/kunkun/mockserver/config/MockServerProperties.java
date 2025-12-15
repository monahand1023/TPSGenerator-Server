package io.kunkun.mockserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

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

    private Persistence persistence = new Persistence();

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

    public Persistence getPersistence() {
        return persistence;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
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
