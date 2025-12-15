package io.kunkun.mockserver.service;

import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MockEndpointServiceTest {

    private MockEndpointService service;
    private MockServerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MockServerProperties();
        properties.setDefaultMinDelay(10);
        properties.setDefaultMaxDelay(100);
        properties.setDefaultErrorRate(0.0);
        service = new MockEndpointService(properties);
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
}
