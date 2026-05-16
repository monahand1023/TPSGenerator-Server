package io.kunkun.mockserver.controller;

import io.kunkun.mockserver.dto.ApiResponse;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import io.kunkun.mockserver.dto.RequestRecord;
import io.kunkun.mockserver.service.MockEndpointService;
import io.kunkun.mockserver.service.RequestHistoryService;
import io.kunkun.mockserver.service.StatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class MockRequestController {

    private static final Logger logger = LoggerFactory.getLogger(MockRequestController.class);

    private final MockEndpointService endpointService;
    private final StatisticsService statisticsService;
    private final RequestHistoryService requestHistoryService;

    public MockRequestController(
            MockEndpointService endpointService,
            StatisticsService statisticsService,
            RequestHistoryService requestHistoryService) {
        this.endpointService = endpointService;
        this.statisticsService = statisticsService;
        this.requestHistoryService = requestHistoryService;
    }

    @RequestMapping("/{path}/**")
    public ResponseEntity<Object> handleRequest(
            @PathVariable String path,
            @RequestBody(required = false) String requestBody,
            @RequestParam Map<String, String> requestParams,
            @RequestHeader Map<String, String> headers,
            HttpServletRequest request) {

        // Record request (global counter)
        long requestId = statisticsService.incrementAndGetRequestId();
        statisticsService.recordRequest();

        // Log incoming request — sanitize URI to prevent log injection
        String safeUri = request.getRequestURI()
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        logger.info("Received request #{}: {} {} - Headers count: {}",
                requestId, request.getMethod(), safeUri, headers.size());

        // Extract full path from URI (fixes bug where only first segment was used)
        String fullPath = request.getRequestURI();
        if (fullPath.startsWith("/")) {
            fullPath = fullPath.substring(1);
        }

        // Per-endpoint request counter
        statisticsService.recordRequest(fullPath);

        // Get configuration
        MockEndpointConfig config = endpointService.getEffectiveConfig(fullPath);

        // Apply delay using thread-safe ThreadLocalRandom
        int delay = ThreadLocalRandom.current().nextInt(
                config.getMinDelay(), config.getMaxDelay() + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Check for simulated error using thread-safe ThreadLocalRandom
        if (ThreadLocalRandom.current().nextDouble() < config.getErrorRate()) {
            recordHistory(request.getMethod(), fullPath, headers, requestBody, HttpStatus.INTERNAL_SERVER_ERROR.value(), delay);
            return handleError(requestId, delay, fullPath);
        }

        recordHistory(request.getMethod(), fullPath, headers, requestBody, HttpStatus.OK.value(), delay);
        return handleSuccess(requestId, delay, config, headers, requestParams, requestBody, fullPath);
    }

    /**
     * Creates and stores a {@link RequestRecord} for the given request attributes.
     */
    private void recordHistory(
            String method,
            String path,
            Map<String, String> headers,
            String requestBody,
            int responseStatus,
            long processingTimeMs) {
        RequestRecord record = new RequestRecord(
                System.currentTimeMillis(),
                method,
                path,
                headers,
                requestBody,
                responseStatus,
                processingTimeMs);
        requestHistoryService.record(record);
    }

    private ResponseEntity<Object> handleError(long requestId, int delay, String endpoint) {
        statisticsService.recordFailure();
        statisticsService.recordFailure(endpoint);
        statisticsService.recordProcessingTime(delay);
        statisticsService.recordProcessingTime(delay, endpoint);

        logger.info("Completed request #{}: Status 500 - Response time: {}ms", requestId, delay);

        Map<String, Object> response = ApiResponse.error()
                .withMessage("Simulated error")
                .withRequestId(requestId)
                .withProcessingTime(delay)
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private ResponseEntity<Object> handleSuccess(
            long requestId,
            int delay,
            MockEndpointConfig config,
            Map<String, String> headers,
            Map<String, String> requestParams,
            String requestBody,
            String endpoint) {

        statisticsService.recordSuccess();
        statisticsService.recordSuccess(endpoint);
        statisticsService.recordProcessingTime(delay);
        statisticsService.recordProcessingTime(delay, endpoint);

        ApiResponse response = ApiResponse.success()
                .withMessage(config.getResponseMessage())
                .withRequestId(requestId)
                .withProcessingTime(delay)
                .with("headers", headers)
                .with("params", requestParams);

        if (requestBody != null && !requestBody.isEmpty()) {
            response.with("requestBody", requestBody);
        }

        // Build response with custom headers
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
        for (Map.Entry<String, Object> header : config.getResponseHeaders().entrySet()) {
            responseBuilder.header(header.getKey(), header.getValue().toString());
        }

        logger.info("Completed request #{}: Status 200 - Response time: {}ms", requestId, delay);

        return responseBuilder.body(response.build());
    }
}
