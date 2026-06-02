package io.kunkun.mockserver.controller;

import io.kunkun.mockserver.config.MockServerProperties;
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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class MockRequestController {

    private static final Logger logger = LoggerFactory.getLogger(MockRequestController.class);

    /** Single bounded bucket for all unconfigured paths, so metric/history cardinality stays bounded. */
    private static final String UNMATCHED_ENDPOINT = "(unmatched)";

    /**
     * Response headers a caller may NOT override — they are managed by the framework/transport,
     * and overriding them produces malformed or duplicate headers.
     */
    private static final Set<String> FORBIDDEN_RESPONSE_HEADERS = Set.of(
            "content-length", "transfer-encoding", "connection", "keep-alive", "upgrade", "host");

    private final MockEndpointService endpointService;
    private final StatisticsService statisticsService;
    private final RequestHistoryService requestHistoryService;
    private final boolean historyEnabled;

    public MockRequestController(
            MockEndpointService endpointService,
            StatisticsService statisticsService,
            RequestHistoryService requestHistoryService,
            MockServerProperties properties) {
        this.endpointService = endpointService;
        this.statisticsService = statisticsService;
        this.requestHistoryService = requestHistoryService;
        this.historyEnabled = properties.getHistory().isEnabled();
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

        // Log incoming request at DEBUG only — at high TPS, per-request INFO logging (plus the
        // URI sanitization it requires) is significant allocation + appender contention.
        if (logger.isDebugEnabled()) {
            String safeUri = request.getRequestURI()
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");
            logger.debug("Received request #{}: {} {} - Headers count: {}",
                    requestId, request.getMethod(), safeUri, headers.size());
        }

        // Extract full path from URI (handles multi-segment paths)
        String fullPath = request.getRequestURI();
        if (fullPath.startsWith("/")) {
            fullPath = fullPath.substring(1);
        }

        // Single cache lookup decides both the effective config and whether this is a known
        // endpoint. Unconfigured paths collapse to one bounded metric/history label.
        String normalized = MockEndpointService.normalizePath(fullPath);
        MockEndpointConfig matched = endpointService.findConfig(normalized);
        MockEndpointConfig config = matched != null ? matched : endpointService.getDefaultConfig();
        String endpointLabel = matched != null ? normalized : UNMATCHED_ENDPOINT;

        // Per-endpoint request counter (bounded label)
        statisticsService.recordRequest(endpointLabel);

        // Apply delay using thread-safe ThreadLocalRandom. On a virtual thread this blocking
        // sleep unmounts the carrier, so many in-flight delayed requests share few OS threads.
        int plannedDelay = ThreadLocalRandom.current().nextInt(
                config.getMinDelay(), config.getMaxDelay() + 1);
        int delay = plannedDelay;
        long sleepStart = System.nanoTime();
        try {
            Thread.sleep(plannedDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // We slept less than planned; report the actual elapsed time, not the planned delay.
            delay = (int) Math.min(plannedDelay, (System.nanoTime() - sleepStart) / 1_000_000L);
        }

        // Check for simulated error — short-circuit the RNG call when errors are disabled.
        double errorRate = config.getErrorRate();
        if (errorRate > 0.0 && ThreadLocalRandom.current().nextDouble() < errorRate) {
            recordHistory(request.getMethod(), endpointLabel, headers, requestBody, HttpStatus.INTERNAL_SERVER_ERROR.value(), delay);
            return handleError(requestId, delay, endpointLabel);
        }

        recordHistory(request.getMethod(), endpointLabel, headers, requestBody, HttpStatus.OK.value(), delay);
        return handleSuccess(requestId, delay, config, headers, requestParams, requestBody, endpointLabel);
    }

    /**
     * Creates and stores a {@link RequestRecord} when history is enabled. History is off by
     * default because it allocates a record (with a defensive header copy) per request — pure
     * overhead on a high-TPS load target.
     */
    private void recordHistory(
            String method,
            String endpointLabel,
            Map<String, String> headers,
            String requestBody,
            int responseStatus,
            long processingTimeMs) {
        if (!historyEnabled) {
            return;
        }
        RequestRecord record = new RequestRecord(
                System.currentTimeMillis(),
                method,
                endpointLabel,
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

        if (logger.isDebugEnabled()) {
            logger.debug("Completed request #{}: Status 500 - Response time: {}ms", requestId, delay);
        }

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

        // Build response with custom headers, skipping framework-managed ones and stripping
        // CR/LF to prevent response-header injection from a malicious/typo'd config value.
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
        for (Map.Entry<String, Object> header : config.getResponseHeaders().entrySet()) {
            String name = header.getKey();
            if (name == null || FORBIDDEN_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
                continue;
            }
            String value = String.valueOf(header.getValue()).replace("\r", "").replace("\n", "");
            responseBuilder.header(name, value);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Completed request #{}: Status 200 - Response time: {}ms", requestId, delay);
        }

        return responseBuilder.body(response.build());
    }
}
