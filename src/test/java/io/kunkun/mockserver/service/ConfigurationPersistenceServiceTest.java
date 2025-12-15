package io.kunkun.mockserver.service;

import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationPersistenceServiceTest {

    @TempDir
    Path tempDir;

    private MockServerProperties properties;
    private MockEndpointService endpointService;
    private ConfigurationPersistenceService persistenceService;
    private File configFile;

    @BeforeEach
    void setUp() {
        properties = new MockServerProperties();
        endpointService = new MockEndpointService(properties);

        configFile = tempDir.resolve("test-config.json").toFile();
        properties.getPersistence().setFilePath(configFile.getAbsolutePath());
    }

    // ========== Persistence Disabled Tests ==========

    @Test
    void saveConfigurations_whenDisabled_returnsFalse() {
        properties.getPersistence().setEnabled(false);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        boolean result = persistenceService.saveConfigurations();

        assertFalse(result);
        assertFalse(configFile.exists());
    }

    @Test
    void isPersistenceEnabled_whenDisabled_returnsFalse() {
        properties.getPersistence().setEnabled(false);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        assertFalse(persistenceService.isPersistenceEnabled());
    }

    @Test
    void isPersistenceEnabled_whenEnabled_returnsTrue() {
        properties.getPersistence().setEnabled(true);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        assertTrue(persistenceService.isPersistenceEnabled());
    }

    // ========== Save Tests ==========

    @Test
    void saveConfigurations_whenEnabled_createsFile() {
        properties.getPersistence().setEnabled(true);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        // Add some configurations
        endpointService.configureEndpoint("api/users",
                new MockEndpointConfig(10, 100, 0.1, new HashMap<>(), "Users endpoint"));
        endpointService.configureEndpoint("api/products",
                new MockEndpointConfig(20, 200, 0.2, new HashMap<>(), "Products endpoint"));

        boolean result = persistenceService.saveConfigurations();

        assertTrue(result);
        assertTrue(configFile.exists());
    }

    @Test
    void saveConfigurations_writesValidJson() throws IOException {
        properties.getPersistence().setEnabled(true);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        endpointService.configureEndpoint("test-endpoint",
                new MockEndpointConfig(50, 150, 0.05, new HashMap<>(), "Test"));

        persistenceService.saveConfigurations();

        String content = Files.readString(configFile.toPath());
        assertTrue(content.contains("test-endpoint"));
        assertTrue(content.contains("minDelay"));
        assertTrue(content.contains("50"));
    }

    @Test
    void saveConfigurations_withEmptyConfigs_createsEmptyJson() throws IOException {
        properties.getPersistence().setEnabled(true);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        boolean result = persistenceService.saveConfigurations();

        assertTrue(result);
        String content = Files.readString(configFile.toPath());
        assertEquals("{ }", content.trim());
    }

    // ========== Load Tests ==========

    @Test
    void loadConfigurations_whenFileNotExists_doesNotFail() {
        properties.getPersistence().setEnabled(true);
        properties.getPersistence().setFilePath(tempDir.resolve("nonexistent.json").toString());
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        // Should not throw
        assertDoesNotThrow(() -> persistenceService.loadConfigurationsOnStartup());
        assertEquals(0, endpointService.getConfiguredEndpointCount());
    }

    @Test
    void loadConfigurations_restoresEndpoints() throws IOException {
        properties.getPersistence().setEnabled(true);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        // Create a config file manually
        String json = """
                {
                    "api/users": {
                        "minDelay": 10,
                        "maxDelay": 100,
                        "errorRate": 0.1,
                        "responseHeaders": {},
                        "responseMessage": "Users"
                    },
                    "api/products": {
                        "minDelay": 20,
                        "maxDelay": 200,
                        "errorRate": 0.2,
                        "responseHeaders": {},
                        "responseMessage": "Products"
                    }
                }
                """;
        Files.writeString(configFile.toPath(), json);

        persistenceService.loadConfigurationsOnStartup();

        assertEquals(2, endpointService.getConfiguredEndpointCount());
        assertTrue(endpointService.getEndpointConfig("api/users").isPresent());
        assertTrue(endpointService.getEndpointConfig("api/products").isPresent());
    }

    @Test
    void loadConfigurations_skipsInvalidConfigs() throws IOException {
        properties.getPersistence().setEnabled(true);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        // Create a config file with one valid and one invalid entry
        String json = """
                {
                    "valid-endpoint": {
                        "minDelay": 10,
                        "maxDelay": 100,
                        "errorRate": 0.1,
                        "responseHeaders": {},
                        "responseMessage": "Valid"
                    },
                    "invalid-endpoint": {
                        "minDelay": 200,
                        "maxDelay": 100,
                        "errorRate": 0.1,
                        "responseHeaders": {},
                        "responseMessage": "Invalid - minDelay > maxDelay"
                    }
                }
                """;
        Files.writeString(configFile.toPath(), json);

        persistenceService.loadConfigurationsOnStartup();

        // Only valid endpoint should be loaded
        assertEquals(1, endpointService.getConfiguredEndpointCount());
        assertTrue(endpointService.getEndpointConfig("valid-endpoint").isPresent());
        assertFalse(endpointService.getEndpointConfig("invalid-endpoint").isPresent());
    }

    // ========== Round-Trip Tests ==========

    @Test
    void saveAndLoad_preservesConfigurations() {
        properties.getPersistence().setEnabled(true);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        // Configure some endpoints
        endpointService.configureEndpoint("api/test",
                new MockEndpointConfig(25, 75, 0.15, new HashMap<>(), "Test message"));

        // Save
        persistenceService.saveConfigurations();

        // Clear and create fresh service
        endpointService.clearAllConfigurations();
        assertEquals(0, endpointService.getConfiguredEndpointCount());

        // Load
        persistenceService.loadConfigurationsOnStartup();

        // Verify
        assertEquals(1, endpointService.getConfiguredEndpointCount());
        MockEndpointConfig loaded = endpointService.getEndpointConfig("api/test").orElseThrow();
        assertEquals(25, loaded.getMinDelay());
        assertEquals(75, loaded.getMaxDelay());
        assertEquals(0.15, loaded.getErrorRate());
        assertEquals("Test message", loaded.getResponseMessage());
    }

    // ========== File Path Tests ==========

    @Test
    void getPersistenceFilePath_returnsConfiguredPath() {
        String expectedPath = "/custom/path/config.json";
        properties.getPersistence().setFilePath(expectedPath);
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        assertEquals(expectedPath, persistenceService.getPersistenceFilePath());
    }

    @Test
    void saveConfigurations_createsParentDirectories() {
        properties.getPersistence().setEnabled(true);
        Path nestedPath = tempDir.resolve("nested/deep/config.json");
        properties.getPersistence().setFilePath(nestedPath.toString());
        persistenceService = new ConfigurationPersistenceService(properties, endpointService);

        boolean result = persistenceService.saveConfigurations();

        assertTrue(result);
        assertTrue(nestedPath.toFile().exists());
    }
}
