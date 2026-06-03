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

    /**
     * Cached immutable default {@link MockEndpointConfig}. A load target mostly hits
     * unconfigured paths, so allocating a fresh default config (plus a HashMap) on every
     * such request was pure hot-path garbage. Rebuilt only when {@link #updateDefaults} runs.
     */
    private final AtomicReference<MockEndpointConfig> defaultConfigCache;

    /** Single persistence mechanism: enabled flag + file path, sourced from {@link MockServerProperties}. */
    private volatile boolean persistenceEnabled;
    private volatile String configFilePath;

    private final ObjectMapper objectMapper;

    /** Serializes concurrent calls to the file writer so the .tmp file is never written by two threads at once. */
    private final ReentrantLock persistLock = new ReentrantLock();

    public MockEndpointService(MockServerProperties properties) {
        this.properties = properties;
        this.endpointConfigs = Caffeine.newBuilder()
                .maximumSize(properties.getMaxEndpointConfigs())
                .build();
        DefaultConfig initialDefaults = new DefaultConfig(
                properties.getDefaultMinDelay(),
                properties.getDefaultMaxDelay(),
                properties.getDefaultErrorRate()
        );
        this.defaults = new AtomicReference<>(initialDefaults);
        this.defaultConfigCache = new AtomicReference<>(buildDefaultConfig(initialDefaults));
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.persistenceEnabled = properties.getPersistence().isEnabled();
        this.configFilePath = properties.getPersistence().getFilePath();
    }

    /** Package-private setter to allow unit tests to redirect file I/O to a temp path. */
    void setConfigFilePath(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    /** Package-private setter to allow unit tests to toggle persistence without a Spring context. */
    void setPersistenceEnabled(boolean persistenceEnabled) {
        this.persistenceEnabled = persistenceEnabled;
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public String getPersistenceFilePath() {
        return configFilePath;
    }

    /** On startup, auto-load persisted configs only when persistence is enabled. */
    @PostConstruct
    public void loadOnStartup() {
        if (persistenceEnabled) {
            loadFromFile();
        } else {
            logger.info("Configuration persistence is disabled");
        }
    }

    /**
     * Explicitly (re)loads configurations from the configured file, replacing nothing that
     * isn't present in the file (entries are merged/overwritten by key). Safe to call directly.
     */
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
        MockEndpointConfig config = findConfig(path);
        return config != null ? config : createDefaultConfig();
    }

    /**
     * Returns the explicitly-configured config for {@code path}, or {@code null} if the path
     * is unconfigured. Lets the hot path do a single cache lookup and decide both the effective
     * config and whether the path is a known endpoint (for bounded metric tagging).
     */
    public MockEndpointConfig findConfig(String path) {
        return endpointConfigs.getIfPresent(normalizePath(path));
    }

    /** True if {@code path} maps to an explicitly-configured endpoint. */
    public boolean isConfigured(String path) {
        return findConfig(path) != null;
    }

    /** Shared immutable default config (see {@link #defaultConfigCache}). */
    public MockEndpointConfig getDefaultConfig() {
        return defaultConfigCache.get();
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

    /** Auto-save hook for mutations; a no-op unless persistence is enabled. */
    private void persistToFile() {
        if (persistenceEnabled) {
            writeToFile();
        }
    }

    /**
     * Explicitly saves the current configuration snapshot to the configured file,
     * regardless of the enabled flag (callers gate on {@link #isPersistenceEnabled()}).
     * @return true if the write succeeded
     */
    public boolean saveToFile() {
        return writeToFile();
    }

    /**
     * Clears the in-memory cache and reloads it from the configured file.
     * @return the number of endpoints loaded
     */
    public int reloadFromFile() {
        endpointConfigs.invalidateAll();
        loadFromFile();
        return getConfiguredEndpointCount();
    }

    /**
     * Serializes the full cache snapshot to the configured JSON file.
     * Writes to a .tmp file first, then renames for near-atomic replacement.
     * Falls back to a non-atomic replace if ATOMIC_MOVE is unsupported
     * (e.g. when source and target are on different filesystems).
     * Uses a lock so concurrent write operations don't collide on the .tmp file.
     */
    private boolean writeToFile() {
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
            return true;
        } catch (IOException e) {
            File target = new File(configFilePath);
            logger.warn("Failed to persist mock-endpoint configs to '{}': {}", target.getAbsolutePath(), e.getMessage());
            // Don't leave a partial/orphaned .tmp behind on failure.
            new File(configFilePath + ".tmp").delete();
            return false;
        } finally {
            persistLock.unlock();
        }
    }

    /** Returns the shared cached default config. Retained for backward compatibility. */
    public MockEndpointConfig createDefaultConfig() {
        return defaultConfigCache.get();
    }

    /** Builds an immutable default config from the current defaults (empty, unmodifiable headers). */
    private static MockEndpointConfig buildDefaultConfig(DefaultConfig d) {
        return new MockEndpointConfig(
                d.minDelay,
                d.maxDelay,
                d.errorRate,
                java.util.Collections.emptyMap(),
                "Default response"
        );
    }

    public void updateDefaults(Integer minDelay, Integer maxDelay, Double errorRate) {
        // Atomically update defaults by creating new immutable config.
        // Individual scalar constraints are also enforced by JSR-380 @RequestParam annotations in
        // the controller so HTTP callers get consistent 400 responses before reaching this method.
        // The service-layer checks here protect programmatic (non-HTTP) callers.
        DefaultConfig updated = defaults.updateAndGet(current -> {
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
        // Rebuild the cached immutable default so the hot path keeps reusing one instance.
        defaultConfigCache.set(buildDefaultConfig(updated));
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
