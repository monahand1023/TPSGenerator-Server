package io.kunkun.mockserver.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for persisting endpoint configurations to a JSON file.
 * Configurations are automatically loaded on startup and saved on shutdown.
 */
@Service
public class ConfigurationPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationPersistenceService.class);

    private final MockServerProperties properties;
    private final MockEndpointService endpointService;
    private final ObjectMapper objectMapper;

    public ConfigurationPersistenceService(MockServerProperties properties, MockEndpointService endpointService) {
        this.properties = properties;
        this.endpointService = endpointService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void loadConfigurationsOnStartup() {
        if (!properties.getPersistence().isEnabled()) {
            logger.info("Configuration persistence is disabled");
            return;
        }

        File configFile = new File(properties.getPersistence().getFilePath());
        if (!configFile.exists()) {
            logger.info("No persisted configuration file found at: {}", configFile.getAbsolutePath());
            return;
        }

        try {
            Map<String, MockEndpointConfig> configs = objectMapper.readValue(
                    configFile,
                    new TypeReference<Map<String, MockEndpointConfig>>() {}
            );

            int loadedCount = 0;
            for (Map.Entry<String, MockEndpointConfig> entry : configs.entrySet()) {
                try {
                    endpointService.configureEndpoint(entry.getKey(), entry.getValue());
                    loadedCount++;
                } catch (IllegalArgumentException e) {
                    logger.warn("Skipping invalid configuration for path '{}': {}",
                            entry.getKey(), e.getMessage());
                }
            }

            logger.info("Loaded {} endpoint configurations from: {}",
                    loadedCount, configFile.getAbsolutePath());

        } catch (IOException e) {
            logger.error("Failed to load configurations from file: {}", configFile.getAbsolutePath(), e);
        }
    }

    @PreDestroy
    public void saveConfigurationsOnShutdown() {
        if (!properties.getPersistence().isEnabled()) {
            return;
        }

        saveConfigurations();
    }

    /**
     * Manually save all current configurations to the persistence file.
     * @return true if save was successful, false otherwise
     */
    public boolean saveConfigurations() {
        if (!properties.getPersistence().isEnabled()) {
            logger.warn("Configuration persistence is disabled, cannot save");
            return false;
        }

        File configFile = new File(properties.getPersistence().getFilePath());
        Map<String, MockEndpointConfig> configs = getAllConfigurations();

        try {
            // Create parent directories if needed
            File parentDir = configFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            objectMapper.writeValue(configFile, configs);
            logger.info("Saved {} endpoint configurations to: {}",
                    configs.size(), configFile.getAbsolutePath());
            return true;

        } catch (IOException e) {
            logger.error("Failed to save configurations to file: {}", configFile.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * Gets all currently configured endpoints from the endpoint service.
     */
    private Map<String, MockEndpointConfig> getAllConfigurations() {
        return endpointService.getAllConfigurations();
    }

    /**
     * Check if persistence is enabled.
     */
    public boolean isPersistenceEnabled() {
        return properties.getPersistence().isEnabled();
    }

    /**
     * Get the configured persistence file path.
     */
    public String getPersistenceFilePath() {
        return properties.getPersistence().getFilePath();
    }
}
