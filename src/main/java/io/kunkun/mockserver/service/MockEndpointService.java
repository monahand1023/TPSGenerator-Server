package io.kunkun.mockserver.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class MockEndpointService {

    private static final Logger logger = LoggerFactory.getLogger(MockEndpointService.class);

    /**
     * Thread-safe LRU cache backed by Caffeine.
     *
     * Replaces the previous LinkedHashMap + ReentrantReadWriteLock implementation which had a
     * concurrency bug: access-order LinkedHashMap.get() mutates internal state (moves the entry
     * to the tail), making it unsafe under a shared read lock. Caffeine provides correct
     * concurrent LRU semantics without any external locking.
     *
     * The maximum size comes from {@link MockServerProperties#getMaxEndpointConfigs()} so it
     * can be overridden via configuration without a code change.
     */
    private final Cache<String, MockEndpointConfig> endpointConfigs;

    private final MockServerProperties properties;

    // Immutable defaults holder to prevent race conditions
    private final AtomicReference<DefaultConfig> defaults;

    @Value("${mock.config.file:mock-endpoints.json}")
    private String configFilePath;

    private final ObjectMapper objectMapper;

    /** Serializes concurrent calls to persistToFile so the .tmp file is never written by two threads at once. */
    private final ReentrantLock persistLock = new ReentrantLock();

    public MockEndpointService(MockServerProperties properties) {
        this.properties = properties;
        this.endpointConfigs = Caffeine.newBuilder()
                .maximumSize(properties.getMaxEndpointConfigs())
                .build();
        this.defaults = new AtomicReference<>(new DefaultConfig(
                properties.getDefaultMinDelay(),
                properties.getDefaultMaxDelay(),
                properties.getDefaultErrorRate()
        ));
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // Default configFilePath for non-Spring contexts (unit tests); overridden by @Value in Spring context
        this.configFilePath = "mock-endpoints.json";
    }

    /** Package-private setter to allow unit tests to redirect file I/O to a temp path. */
    void setConfigFilePath(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    @PostConstruct
    public void loadFromFile() {
        File file = new File(configFilePath);
        if (!file.exists()) {
            logger.info("No mock-endpoint config file found at: {}", file.getAbsolutePath());
            return;
        }

        try {
            Map<String, MockEndpointConfig> configs = objectMapper.readValue(
                    file, new TypeReference<Map<String, MockEndpointConfig>>() {});
            int loaded = 0;
            for (Map.Entry<String, MockEndpointConfig> entry : configs.entrySet()) {
                try {
                    entry.getValue().validate();
                    endpointConfigs.put(entry.getKey(), entry.getValue());
                    loaded++;
                } catch (IllegalArgumentException e) {
                    logger.warn("Skipping invalid config for path '{}': {}", entry.getKey(), e.getMessage());
                }
            }
            logger.info("Loaded {} endpoint configs from: {}", loaded, file.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Could not read mock-endpoint config file '{}', starting empty: {}",
                    file.getAbsolutePath(), e.getMessage());
        }
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
        persistToFile();
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
        persistToFile();
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
        persistToFile();
        return existed;
    }

    /**
     * Serializes the full cache snapshot to the configured JSON file.
     * Writes to a .tmp file first, then renames for near-atomic replacement.
     * Falls back to a non-atomic replace if ATOMIC_MOVE is unsupported
     * (e.g. when source and target are on different filesystems).
     * Uses a lock so concurrent write operations don't collide on the .tmp file.
     */
    private void persistToFile() {
        persistLock.lock();
        try {
            File target = new File(configFilePath);
            File tmp = new File(configFilePath + ".tmp");

            File parentDir = target.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            Map<String, MockEndpointConfig> snapshot = new HashMap<>(endpointConfigs.asMap());
            objectMapper.writeValue(tmp, snapshot);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                // Fall back to non-atomic replace when crossing filesystem boundaries
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            File target = new File(configFilePath);
            logger.warn("Failed to persist mock-endpoint configs to '{}': {}", target.getAbsolutePath(), e.getMessage());
        } finally {
            persistLock.unlock();
        }
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
        // Atomically update defaults by creating new immutable config.
        // Individual scalar constraints are also enforced by JSR-380 @RequestParam annotations in
        // the controller so HTTP callers get consistent 400 responses before reaching this method.
        // The service-layer checks here protect programmatic (non-HTTP) callers.
        defaults.updateAndGet(current -> {
            int newMinDelay = minDelay != null ? minDelay : current.minDelay;
            int newMaxDelay = maxDelay != null ? maxDelay : current.maxDelay;
            double newErrorRate = errorRate != null ? errorRate : current.errorRate;

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
