package io.kunkun.mockserver.controller;

import io.kunkun.mockserver.dto.ApiResponse;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import io.kunkun.mockserver.service.ConfigurationPersistenceService;
import io.kunkun.mockserver.service.MockEndpointService;
import io.kunkun.mockserver.service.StatisticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

/**
 * Admin controller for managing mock server configuration.
 * Available at both /admin and /api/v1/admin for versioned access.
 */
@RestController
public class AdminController {

    private final MockEndpointService endpointService;
    private final StatisticsService statisticsService;
    private final ConfigurationPersistenceService persistenceService;

    public AdminController(
            MockEndpointService endpointService,
            StatisticsService statisticsService,
            ConfigurationPersistenceService persistenceService) {
        this.endpointService = endpointService;
        this.statisticsService = statisticsService;
        this.persistenceService = persistenceService;
    }

    // ========== Endpoint Configuration ==========

    @PostMapping({"/admin/config/{path}", "/api/v1/admin/config/{path}"})
    public ResponseEntity<Map<String, Object>> configureEndpoint(
            @PathVariable String path,
            @RequestBody @Valid MockEndpointConfig config) {

        endpointService.configureEndpoint(path, config);

        return ResponseEntity.ok(
                ApiResponse.success()
                        .withMessage("Endpoint configured: /" + path)
                        .with("config", config)
                        .build()
        );
    }

    @GetMapping({"/admin/config/{path}", "/api/v1/admin/config/{path}"})
    public ResponseEntity<MockEndpointConfig> getEndpointConfig(@PathVariable String path) {
        return endpointService.getEndpointConfig(path)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping({"/admin/config", "/api/v1/admin/config"})
    public ResponseEntity<Map<String, Object>> getAllEndpointConfigs() {
        return ResponseEntity.ok(
                ApiResponse.success()
                        .with("endpoints", endpointService.getAllConfigurations())
                        .with("count", endpointService.getConfiguredEndpointCount())
                        .build()
        );
    }

    @DeleteMapping({"/admin/config/{path}", "/api/v1/admin/config/{path}"})
    public ResponseEntity<Map<String, Object>> deleteEndpointConfig(@PathVariable String path) {
        boolean deleted = endpointService.deleteEndpoint(path);

        if (deleted) {
            return ResponseEntity.ok(
                    ApiResponse.success()
                            .withMessage("Endpoint configuration deleted: /" + path)
                            .build()
            );
        } else {
            return ResponseEntity.status(404).body(
                    ApiResponse.error()
                            .withMessage("Endpoint not found: /" + path)
                            .build()
            );
        }
    }

    @DeleteMapping({"/admin/config", "/api/v1/admin/config"})
    public ResponseEntity<Map<String, Object>> clearAllEndpointConfigs() {
        int count = endpointService.getConfiguredEndpointCount();
        endpointService.clearAllConfigurations();

        return ResponseEntity.ok(
                ApiResponse.success()
                        .withMessage("All endpoint configurations cleared")
                        .with("deletedCount", count)
                        .build()
        );
    }

    // ========== Default Configuration ==========

    @PostMapping({"/admin/defaults", "/api/v1/admin/defaults"})
    public ResponseEntity<Map<String, Object>> configureDefaults(
            @RequestParam(required = false) Integer minDelay,
            @RequestParam(required = false) Integer maxDelay,
            @RequestParam(required = false) Double errorRate) {

        endpointService.updateDefaults(minDelay, maxDelay, errorRate);

        return ResponseEntity.ok(
                ApiResponse.success()
                        .with("defaultMinDelay", endpointService.getCurrentMinDelay())
                        .with("defaultMaxDelay", endpointService.getCurrentMaxDelay())
                        .with("defaultErrorRate", endpointService.getCurrentErrorRate())
                        .build()
        );
    }

    @GetMapping({"/admin/defaults", "/api/v1/admin/defaults"})
    public ResponseEntity<Map<String, Object>> getDefaults() {
        return ResponseEntity.ok(
                ApiResponse.success()
                        .with("defaultMinDelay", endpointService.getCurrentMinDelay())
                        .with("defaultMaxDelay", endpointService.getCurrentMaxDelay())
                        .with("defaultErrorRate", endpointService.getCurrentErrorRate())
                        .build()
        );
    }

    // ========== Statistics ==========

    @GetMapping({"/admin/stats", "/api/v1/admin/stats"})
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(statisticsService.getStats());
    }

    @PostMapping({"/admin/stats/reset", "/api/v1/admin/stats/reset"})
    public ResponseEntity<Map<String, Object>> resetStats() {
        statisticsService.reset();

        return ResponseEntity.ok(
                ApiResponse.success()
                        .withMessage("Statistics reset")
                        .build()
        );
    }

    // ========== Persistence ==========

    @PostMapping({"/admin/persistence/save", "/api/v1/admin/persistence/save"})
    public ResponseEntity<Map<String, Object>> saveConfigurations() {
        if (!persistenceService.isPersistenceEnabled()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error()
                            .withMessage("Persistence is disabled. Enable it in application.properties")
                            .build()
            );
        }

        boolean success = persistenceService.saveConfigurations();

        if (success) {
            return ResponseEntity.ok(
                    ApiResponse.success()
                            .withMessage("Configurations saved successfully")
                            .with("filePath", persistenceService.getPersistenceFilePath())
                            .with("endpointCount", endpointService.getConfiguredEndpointCount())
                            .build()
            );
        } else {
            return ResponseEntity.internalServerError().body(
                    ApiResponse.error()
                            .withMessage("Failed to save configurations")
                            .build()
            );
        }
    }

    @PostMapping({"/admin/persistence/load", "/api/v1/admin/persistence/load"})
    public ResponseEntity<Map<String, Object>> loadConfigurations() {
        if (!persistenceService.isPersistenceEnabled()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error()
                            .withMessage("Persistence is disabled. Enable it in application.properties")
                            .build()
            );
        }

        persistenceService.loadConfigurationsOnStartup();

        return ResponseEntity.ok(
                ApiResponse.success()
                        .withMessage("Configurations loaded")
                        .with("filePath", persistenceService.getPersistenceFilePath())
                        .with("endpointCount", endpointService.getConfiguredEndpointCount())
                        .build()
        );
    }

    @GetMapping({"/admin/persistence/status", "/api/v1/admin/persistence/status"})
    public ResponseEntity<Map<String, Object>> getPersistenceStatus() {
        return ResponseEntity.ok(
                ApiResponse.success()
                        .with("enabled", persistenceService.isPersistenceEnabled())
                        .with("filePath", persistenceService.getPersistenceFilePath())
                        .build()
        );
    }
}
