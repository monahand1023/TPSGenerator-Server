package io.kunkun.mockserver.controller;

import io.kunkun.mockserver.dto.ApiResponse;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import io.kunkun.mockserver.dto.RequestRecord;
import io.kunkun.mockserver.service.MockEndpointService;
import io.kunkun.mockserver.service.OpenApiImportService;
import io.kunkun.mockserver.service.RequestHistoryService;
import io.kunkun.mockserver.service.StatisticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * Admin controller for managing mock server configuration.
 * Available at both /admin and /api/v1/admin for versioned access.
 *
 * The class-level @RequestMapping covers both URL prefixes so individual methods
 * only specify the relative path segment.
 */
@RestController
@Validated
@RequestMapping({"/admin", "/api/v1/admin"})
public class AdminController {

    private final MockEndpointService endpointService;
    private final StatisticsService statisticsService;
    private final RequestHistoryService requestHistoryService;
    private final OpenApiImportService openApiImportService;

    public AdminController(
            MockEndpointService endpointService,
            StatisticsService statisticsService,
            RequestHistoryService requestHistoryService,
            OpenApiImportService openApiImportService) {
        this.endpointService = endpointService;
        this.statisticsService = statisticsService;
        this.requestHistoryService = requestHistoryService;
        this.openApiImportService = openApiImportService;
    }

    // ========== Endpoint Configuration ==========

    @PostMapping("/config/{path}")
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

    @GetMapping("/config/{path}")
    public ResponseEntity<MockEndpointConfig> getEndpointConfig(@PathVariable String path) {
        return endpointService.getEndpointConfig(path)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getAllEndpointConfigs() {
        return ResponseEntity.ok(
                ApiResponse.success()
                        .with("endpoints", endpointService.getAllConfigurations())
                        .with("count", endpointService.getConfiguredEndpointCount())
                        .build()
        );
    }

    @DeleteMapping("/config/{path}")
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

    @DeleteMapping("/config")
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

    @PostMapping("/defaults")
    public ResponseEntity<Map<String, Object>> configureDefaults(
            @RequestParam(required = false) @jakarta.validation.constraints.Min(value = 0, message = "minDelay must be non-negative") Integer minDelay,
            @RequestParam(required = false) @jakarta.validation.constraints.Min(value = 0, message = "maxDelay must be non-negative") Integer maxDelay,
            @RequestParam(required = false) @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "errorRate must be between 0.0 and 1.0") @jakarta.validation.constraints.DecimalMax(value = "1.0", message = "errorRate must be between 0.0 and 1.0") Double errorRate) {

        endpointService.updateDefaults(minDelay, maxDelay, errorRate);

        return ResponseEntity.ok(
                ApiResponse.success()
                        .with("defaultMinDelay", endpointService.getCurrentMinDelay())
                        .with("defaultMaxDelay", endpointService.getCurrentMaxDelay())
                        .with("defaultErrorRate", endpointService.getCurrentErrorRate())
                        .build()
        );
    }

    @GetMapping("/defaults")
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

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = statisticsService.getStats();
        ApiResponse response = ApiResponse.success();
        stats.forEach(response::with);
        return ResponseEntity.ok(response.build());
    }

    @PostMapping("/stats/reset")
    public ResponseEntity<Map<String, Object>> resetStats() {
        statisticsService.reset();

        return ResponseEntity.ok(
                ApiResponse.success()
                        .withMessage("Statistics reset")
                        .build()
        );
    }

    // ========== Persistence ==========

    @PostMapping("/persistence/save")
    public ResponseEntity<Map<String, Object>> saveConfigurations() {
        if (!endpointService.isPersistenceEnabled()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error()
                            .withMessage("Persistence is disabled. Enable it in application.properties")
                            .build()
            );
        }

        boolean success = endpointService.saveToFile();

        if (success) {
            return ResponseEntity.ok(
                    ApiResponse.success()
                            .withMessage("Configurations saved successfully")
                            .with("filePath", endpointService.getPersistenceFilePath())
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

    @PostMapping("/persistence/load")
    public ResponseEntity<Map<String, Object>> loadConfigurations() {
        if (!endpointService.isPersistenceEnabled()) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error()
                            .withMessage("Persistence is disabled. Enable it in application.properties")
                            .build()
            );
        }

        int count = endpointService.reloadFromFile();

        return ResponseEntity.ok(
                ApiResponse.success()
                        .withMessage("Configurations loaded")
                        .with("filePath", endpointService.getPersistenceFilePath())
                        .with("endpointCount", count)
                        .build()
        );
    }

    @GetMapping("/persistence/status")
    public ResponseEntity<Map<String, Object>> getPersistenceStatus() {
        return ResponseEntity.ok(
                ApiResponse.success()
                        .with("enabled", endpointService.isPersistenceEnabled())
                        .with("filePath", endpointService.getPersistenceFilePath())
                        .build()
        );
    }

    // ========== OpenAPI Import ==========

    @PostMapping("/openapi/import")
    public ResponseEntity<Map<String, Object>> importOpenApi(@RequestBody Map<String, Object> spec) {
        try {
            List<String> imported = openApiImportService.importSpec(spec);
            return ResponseEntity.ok(
                    ApiResponse.success()
                            .withMessage("Imported " + imported.size() + " endpoints from OpenAPI spec")
                            .with("importedCount", imported.size())
                            .with("endpoints", imported)
                            .build()
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error().withMessage(e.getMessage()).build()
            );
        }
    }

    // ========== Request History ==========

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getAllHistory() {
        Map<String, List<RequestRecord>> all = requestHistoryService.getAllHistory();
        return ResponseEntity.ok(
                ApiResponse.success()
                        .with("history", all)
                        .with("endpointCount", all.size())
                        .build()
        );
    }

    @GetMapping("/history/{path}")
    public ResponseEntity<Map<String, Object>> getHistoryForEndpoint(@PathVariable String path) {
        List<RequestRecord> records = requestHistoryService.getHistory(path);
        if (records.isEmpty()) {
            return ResponseEntity.status(404).body(
                    ApiResponse.error()
                            .withMessage("No history found for endpoint: /" + path)
                            .build()
            );
        }
        return ResponseEntity.ok(
                ApiResponse.success()
                        .with("endpoint", path)
                        .with("records", records)
                        .with("count", records.size())
                        .build()
        );
    }

    @DeleteMapping("/history/{path}")
    public ResponseEntity<Map<String, Object>> clearHistoryForEndpoint(@PathVariable String path) {
        requestHistoryService.clearHistory(path);
        return ResponseEntity.ok(
                ApiResponse.success()
                        .withMessage("History cleared for endpoint: /" + path)
                        .build()
        );
    }

    @DeleteMapping("/history")
    public ResponseEntity<Map<String, Object>> clearAllHistory() {
        requestHistoryService.clearAllHistory();
        return ResponseEntity.ok(
                ApiResponse.success()
                        .withMessage("All request history cleared")
                        .build()
        );
    }
}
