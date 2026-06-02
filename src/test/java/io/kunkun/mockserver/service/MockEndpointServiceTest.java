package io.kunkun.mockserver.service;

import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MockEndpointServiceTest {

    @TempDir
    Path tempDir;

    private MockEndpointService service;
    private MockServerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MockServerProperties();
        properties.setDefaultMinDelay(10);
        properties.setDefaultMaxDelay(100);
        properties.setDefaultErrorRate(0.0);
        service = new MockEndpointService(properties);
        // Redirect file I/O to temp dir so tests are hermetic
        service.setConfigFilePath(tempDir.resolve("mock-endpoints.json").toString());
    }

    // ========== Path Normalization Tests ==========

    @Test
    void normalizePath_removesLeadingSlash() {
        assertEquals("api/users", MockEndpointService.normalizePath("/api/users"));
    }

    @Test
    void normalizePath_removesTrailingSlash() {
        assertEquals("api/users", MockEndpointService.normalizePath("api/users/"));
    }

    @Test
    void normalizePath_removesLeadingAndTrailingSlash() {
        assertEquals("api/users", MockEndpointService.normalizePath("/api/users/"));
    }

    @Test
    void normalizePath_convertsToLowercase() {
        assertEquals("api/users", MockEndpointService.normalizePath("API/USERS"));
        assertEquals("api/users", MockEndpointService.normalizePath("Api/Users"));
    }

    @Test
    void normalizePath_handlesEmptyAndNull() {
        assertEquals("", MockEndpointService.normalizePath(""));
        assertEquals("", MockEndpointService.normalizePath(null));
    }

    @Test
    void normalizePath_handlesSingleSlash() {
        assertEquals("", MockEndpointService.normalizePath("/"));
    }

    @Test
    void configureEndpoint_withTrailingSlash_matchesWithoutSlash() {
        MockEndpointConfig config = new MockEndpointConfig(50, 150, 0.1, new HashMap<>(), "Test");

        service.configureEndpoint("api/users/", config);

        Optional<MockEndpointConfig> retrieved = service.getEndpointConfig("api/users");
        assertTrue(retrieved.isPresent());
        assertEquals("Test", retrieved.get().getResponseMessage());
    }

    @Test
    void configureEndpoint_caseInsensitive() {
        MockEndpointConfig config = new MockEndpointConfig(50, 150, 0.1, new HashMap<>(), "Test");

        service.configureEndpoint("API/Users", config);

        Optional<MockEndpointConfig> retrieved = service.getEndpointConfig("api/users");
        assertTrue(retrieved.isPresent());

        Optional<MockEndpointConfig> retrieved2 = service.getEndpointConfig("API/USERS");
        assertTrue(retrieved2.isPresent());
    }

    // ========== Endpoint Configuration Tests ==========

    @Test
    void configureEndpoint_storesConfig() {
        MockEndpointConfig config = new MockEndpointConfig(50, 150, 0.1, new HashMap<>(), "Test");

        service.configureEndpoint("test-path", config);

        Optional<MockEndpointConfig> retrieved = service.getEndpointConfig("test-path");
        assertTrue(retrieved.isPresent());
        assertEquals(50, retrieved.get().getMinDelay());
        assertEquals(150, retrieved.get().getMaxDelay());
        assertEquals(0.1, retrieved.get().getErrorRate());
    }

    @Test
    void configureEndpoint_withInvalidConfig_throwsException() {
        MockEndpointConfig config = new MockEndpointConfig(200, 100, 0.1, new HashMap<>(), "Test");

        assertThrows(IllegalArgumentException.class,
                () -> service.configureEndpoint("test-path", config));
    }

    @Test
    void getEndpointConfig_whenNotExists_returnsEmpty() {
        Optional<MockEndpointConfig> config = service.getEndpointConfig("nonexistent");
        assertTrue(config.isEmpty());
    }

    @Test
    void getEffectiveConfig_whenNotConfigured_returnsDefault() {
        MockEndpointConfig config = service.getEffectiveConfig("unconfigured-path");

        assertEquals(properties.getDefaultMinDelay(), config.getMinDelay());
        assertEquals(properties.getDefaultMaxDelay(), config.getMaxDelay());
        assertEquals(properties.getDefaultErrorRate(), config.getErrorRate());
        assertEquals("Default response", config.getResponseMessage());
    }

    @Test
    void getEffectiveConfig_whenConfigured_returnsConfigured() {
        MockEndpointConfig customConfig = new MockEndpointConfig(25, 75, 0.5, new HashMap<>(), "Custom");
        service.configureEndpoint("configured-path", customConfig);

        MockEndpointConfig config = service.getEffectiveConfig("configured-path");

        assertEquals(25, config.getMinDelay());
        assertEquals(75, config.getMaxDelay());
        assertEquals(0.5, config.getErrorRate());
        assertEquals("Custom", config.getResponseMessage());
    }

    // ========== Default Configuration Tests ==========

    @Test
    void updateDefaults_updatesAllValues() {
        service.updateDefaults(20, 200, 0.1);

        assertEquals(20, service.getCurrentMinDelay());
        assertEquals(200, service.getCurrentMaxDelay());
        assertEquals(0.1, service.getCurrentErrorRate());
    }

    @Test
    void updateDefaults_withPartialValues_updatesOnlyProvided() {
        service.updateDefaults(30, null, null);

        assertEquals(30, service.getCurrentMinDelay());
        assertEquals(properties.getDefaultMaxDelay(), service.getCurrentMaxDelay());
        assertEquals(properties.getDefaultErrorRate(), service.getCurrentErrorRate());
    }

    @Test
    void updateDefaults_withNegativeMinDelay_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateDefaults(-5, null, null));
    }

    @Test
    void updateDefaults_withNegativeMaxDelay_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateDefaults(null, -10, null));
    }

    @Test
    void updateDefaults_withMinDelayGreaterThanMaxDelay_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateDefaults(500, 100, null));
    }

    @Test
    void updateDefaults_withInvalidErrorRate_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateDefaults(null, null, 1.5));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateDefaults(null, null, -0.1));
    }

    // ========== Thread Safety Tests ==========

    @Test
    void updateDefaults_isConcurrentlySafe() throws InterruptedException {
        int threadCount = 10;
        int iterationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        int minDelay = (threadId * 10) + (j % 10);
                        int maxDelay = minDelay + 100;
                        double errorRate = (j % 10) / 10.0;

                        try {
                            service.updateDefaults(minDelay, maxDelay, errorRate);

                            // Verify consistency - min should never exceed max
                            int currentMin = service.getCurrentMinDelay();
                            int currentMax = service.getCurrentMaxDelay();
                            if (currentMin > currentMax) {
                                errors.incrementAndGet();
                            }
                        } catch (IllegalArgumentException e) {
                            // Expected in some cases due to race conditions
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, errors.get(), "Should never have min > max due to atomic updates");
    }

    @Test
    void configureEndpoint_isConcurrentlySafe() throws InterruptedException {
        int threadCount = 10;
        int iterationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        String path = "path-" + threadId + "-" + j;
                        MockEndpointConfig config = new MockEndpointConfig(
                                j, j + 100, 0.0, new HashMap<>(), "Test " + j);
                        service.configureEndpoint(path, config);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Verify some configs were stored correctly
        Optional<MockEndpointConfig> config = service.getEndpointConfig("path-0-50");
        assertTrue(config.isPresent());
        assertEquals(50, config.get().getMinDelay());
    }

    // ========== Memory Bounds Tests ==========

    @Test
    void getConfiguredEndpointCount_returnsCorrectCount() {
        assertEquals(0, service.getConfiguredEndpointCount());

        service.configureEndpoint("path1", new MockEndpointConfig(10, 100, 0.0, new HashMap<>(), "Test1"));
        assertEquals(1, service.getConfiguredEndpointCount());

        service.configureEndpoint("path2", new MockEndpointConfig(10, 100, 0.0, new HashMap<>(), "Test2"));
        assertEquals(2, service.getConfiguredEndpointCount());

        // Same path (normalized) should not increase count
        service.configureEndpoint("PATH1", new MockEndpointConfig(20, 200, 0.0, new HashMap<>(), "Test1Updated"));
        assertEquals(2, service.getConfiguredEndpointCount());
    }

    @Test
    void configureEndpoint_updatesExistingPath() {
        service.configureEndpoint("test-path", new MockEndpointConfig(10, 100, 0.0, new HashMap<>(), "Original"));
        service.configureEndpoint("test-path", new MockEndpointConfig(20, 200, 0.0, new HashMap<>(), "Updated"));

        Optional<MockEndpointConfig> config = service.getEndpointConfig("test-path");
        assertTrue(config.isPresent());
        assertEquals("Updated", config.get().getResponseMessage());
        assertEquals(20, config.get().getMinDelay());
    }

    // ========== File-Based Persistence Tests ==========

    @Test
    void filePersistence_writtenConfigsSurviveRestart() {
        String configFile = tempDir.resolve("persist-test.json").toString();
        service.setConfigFilePath(configFile);
        service.setPersistenceEnabled(true); // auto-save on mutation requires persistence enabled

        // Write 3 configs
        service.configureEndpoint("persist/alpha",
                new MockEndpointConfig(10, 50, 0.0, new HashMap<>(), "Alpha"));
        service.configureEndpoint("persist/beta",
                new MockEndpointConfig(20, 60, 0.1, new HashMap<>(), "Beta"));
        service.configureEndpoint("persist/gamma",
                new MockEndpointConfig(30, 70, 0.2, new HashMap<>(), "Gamma"));

        assertEquals(3, service.getConfiguredEndpointCount());

        // Simulate restart: create a fresh service instance pointing at the same file
        MockEndpointService reloaded = new MockEndpointService(properties);
        reloaded.setConfigFilePath(configFile);
        reloaded.loadFromFile();

        // All 3 configs must be present in the new instance
        assertEquals(3, reloaded.getConfiguredEndpointCount());
        assertTrue(reloaded.getEndpointConfig("persist/alpha").isPresent());
        assertEquals("Alpha", reloaded.getEndpointConfig("persist/alpha").get().getResponseMessage());
        assertTrue(reloaded.getEndpointConfig("persist/beta").isPresent());
        assertEquals("Beta", reloaded.getEndpointConfig("persist/beta").get().getResponseMessage());
        assertTrue(reloaded.getEndpointConfig("persist/gamma").isPresent());
        assertEquals("Gamma", reloaded.getEndpointConfig("persist/gamma").get().getResponseMessage());
    }

    @Test
    void filePersistence_malformedFileLogs_andStartsEmpty() throws Exception {
        String configFile = tempDir.resolve("bad.json").toString();
        java.nio.file.Files.writeString(java.nio.file.Path.of(configFile), "{ this is not valid json }}}");

        MockEndpointService fresh = new MockEndpointService(properties);
        fresh.setConfigFilePath(configFile);

        // Should not throw; should start with an empty cache
        assertDoesNotThrow(() -> fresh.loadFromFile());
        assertEquals(0, fresh.getConfiguredEndpointCount());
    }

    @Test
    void filePersistence_deleteEndpoint_updatesFile() {
        String configFile = tempDir.resolve("delete-test.json").toString();
        service.setConfigFilePath(configFile);
        service.setPersistenceEnabled(true); // auto-save on mutation requires persistence enabled

        service.configureEndpoint("keep/me", new MockEndpointConfig(10, 50, 0.0, new HashMap<>(), "Keep"));
        service.configureEndpoint("delete/me", new MockEndpointConfig(10, 50, 0.0, new HashMap<>(), "Delete"));

        service.deleteEndpoint("delete/me");

        // Reload from file and verify deletion persisted
        MockEndpointService reloaded = new MockEndpointService(properties);
        reloaded.setConfigFilePath(configFile);
        reloaded.loadFromFile();

        assertEquals(1, reloaded.getConfiguredEndpointCount());
        assertTrue(reloaded.getEndpointConfig("keep/me").isPresent());
        assertFalse(reloaded.getEndpointConfig("delete/me").isPresent());
    }

    // ========== Consolidated Persistence API Tests ==========

    @Test
    void persistenceDisabled_configureEndpoint_doesNotWriteFile() {
        String configFile = tempDir.resolve("disabled.json").toString();
        service.setConfigFilePath(configFile);
        // persistence is disabled by default — auto-save must not fire
        service.configureEndpoint("api/x", new MockEndpointConfig(10, 50, 0.0, new HashMap<>(), "X"));

        assertFalse(new java.io.File(configFile).exists(),
                "auto-save must not write a file when persistence is disabled");
    }

    @Test
    void saveToFile_writesSnapshot_andReturnsTrue() throws Exception {
        String configFile = tempDir.resolve("explicit-save.json").toString();
        service.setConfigFilePath(configFile);
        service.configureEndpoint("api/y", new MockEndpointConfig(10, 50, 0.0, new HashMap<>(), "Y"));

        boolean ok = service.saveToFile();

        assertTrue(ok);
        String content = java.nio.file.Files.readString(java.nio.file.Path.of(configFile));
        assertTrue(content.contains("api/y"));
    }

    @Test
    void reloadFromFile_replacesInMemoryState() {
        String configFile = tempDir.resolve("reload.json").toString();
        service.setConfigFilePath(configFile);
        service.configureEndpoint("api/keep", new MockEndpointConfig(10, 50, 0.0, new HashMap<>(), "Keep"));
        service.saveToFile();

        // Mutate in-memory state after the snapshot was written
        service.configureEndpoint("api/transient", new MockEndpointConfig(10, 50, 0.0, new HashMap<>(), "Transient"));
        assertEquals(2, service.getConfiguredEndpointCount());

        int loaded = service.reloadFromFile();

        assertEquals(1, loaded);
        assertTrue(service.getEndpointConfig("api/keep").isPresent());
        assertFalse(service.getEndpointConfig("api/transient").isPresent());
    }

    @Test
    void persistenceMetadata_reflectsConfiguration() {
        properties.getPersistence().setEnabled(true);
        properties.getPersistence().setFilePath("/custom/path/config.json");
        MockEndpointService svc = new MockEndpointService(properties);

        assertTrue(svc.isPersistenceEnabled());
        assertEquals("/custom/path/config.json", svc.getPersistenceFilePath());
    }
}
