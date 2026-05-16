package io.kunkun.mockserver.service;

import com.github.ben-manes.caffeine.cache.Cache;
import com.github.ben-manes.caffeine.cache.Caffeine;
import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class MockEndpointService {

    private static final Logger logger = LoggerFactory.getLogger(MockEndpointService.class);

    // Maximum number of endpoint configurations to prevent unbounded memory growth
    private static final int MAX_ENDPOINT_CONFIGS = 10000;

    /**
     * Thread-safe LRU cache backed by Caffeine.
     *
     * Replaces the previous LinkedHashMap + ReentrantReadWriteLock implementation which had a
     * concurrency bug: access-order LinkedHashMap.get() mutates internal state (moves the entry
     * to the tail), making it unsafe under a shared read lock. Caffeine provides correct
     * concurrent LRU semantics without any external locking.
     */
    private final Cache<String, MockEndpointConfig> endpointConfigs =
            Caffeine.newBuilder()
                    .maximumSize(MAX_ENDPOINT_CONFIGS)
                    .build();

    private final MockServerProperties properties;

    // Immutable defaults holder to prevent race conditions
    private final AtomicReference<DefaultConfig> defaults;

    public MockEndpointService(MockServerProperties properties) {
        this.properties = properties;
        this.defaults = new AtomicReference<>(new DefaultConfig(
                properties.getDefaultMinDelay(),
                properties.getDefaultMaxDelay(),
                properties.getDefaultErrorRate()
        ));
    }

    /**
     * Normalizes a path for consistent lookup:
     * - Removes leading slash
     * - Removes trailing slash
     * - Converts to lowercase for case-insensitive matching
     */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        String normalized = path;

        // Remove leading slash
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        // Remove trailing slash
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        // Convert to lowercase for case-insensitive matching
        normalized = normalized.toLowerCase();

        return normalized;
    }

    public void configureEndpoint(String path, MockEndpointConfig config) {
        config.validate();
        String normalizedPath = normalizePath(path);
        endpointConfigs.put(normalizedPath, config);
    }

    public Optional<MockEndpointConfig> getEndpointConfig(String path) {
        String normalizedPath = normalizePath(path);
        return Optional.ofNullable(endpointConfigs.getIfPresent(normalizedPath));
    }

    public MockEndpointConfig getEffectiveConfig(String path) {
        String normalizedPath = normalizePath(path);
        MockEndpointConfig config = endpointConfigs.getIfPresent(normalizedPath);
        return config != null ? config : createDefaultConfig();
    }

    /**
     * Returns the current number of configured endpoints.
     */
    public int getConfiguredEndpointCount() {
        return (int) endpointConfigs.estimatedSize();
    }

    /**
     * Returns a copy of all configured endpoints.
     * Used for persistence and backup.
     */
    public Map<String, MockEndpointConfig> getAllConfigurations() {
        return new HashMap<>(endpointConfigs.asMap());
    }

    /**
     * Clears all endpoint configurations.
     */
    public void clearAllConfigurations() {
        endpointConfigs.invalidateAll();
    }

    /**
     * Deletes a specific endpoint configuration.
     * @param path the endpoint path to delete
     * @return true if the endpoint existed and was deleted, false if it didn't exist
     */
    public boolean deleteEndpoint(String path) {
        String normalizedPath = normalizePath(path);
        boolean existed = endpointConfigs.getIfPresent(normalizedPath) != null;
        endpointConfigs.invalidate(normalizedPath);
        return existed;
    }

    public MockEndpointConfig createDefaultConfig() {
        DefaultConfig current = defaults.get();
        return new MockEndpointConfig(
                current.minDelay,
                current.maxDelay,
                current.errorRate,
                new HashMap<>(),
                "Default response"
        );
    }

    public void updateDefaults(Integer minDelay, Integer maxDelay, Double errorRate) {
        // Atomically update defaults by creating new immutable config
        defaults.updateAndGet(current -> {
            int newMinDelay = minDelay != null ? minDelay : current.minDelay;
            int newMaxDelay = maxDelay != null ? maxDelay : current.maxDelay;
            double newErrorRate = errorRate != null ? errorRate : current.errorRate;

            // Validate before creating new config
            if (newMinDelay < 0) {
                throw new IllegalArgumentException("minDelay must be non-negative");
            }
            if (newMaxDelay < 0) {
                throw new IllegalArgumentException("maxDelay must be non-negative");
            }
            if (newMinDelay > newMaxDelay) {
                throw new IllegalArgumentException("minDelay cannot exceed maxDelay");
            }
            if (newErrorRate < 0.0 || newErrorRate > 1.0) {
                throw new IllegalArgumentException("errorRate must be between 0.0 and 1.0");
            }

            return new DefaultConfig(newMinDelay, newMaxDelay, newErrorRate);
        });
    }

    public int getCurrentMinDelay() {
        return defaults.get().minDelay;
    }

    public int getCurrentMaxDelay() {
        return defaults.get().maxDelay;
    }

    public double getCurrentErrorRate() {
        return defaults.get().errorRate;
    }

    // Immutable holder for default configuration values
    private static class DefaultConfig {
        final int minDelay;
        final int maxDelay;
        final double errorRate;

        DefaultConfig(int minDelay, int maxDelay, double errorRate) {
            this.minDelay = minDelay;
            this.maxDelay = maxDelay;
            this.errorRate = errorRate;
        }
    }
}
