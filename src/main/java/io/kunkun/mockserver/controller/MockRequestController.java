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

        // Apply delay (uniform/normal/lognormal). On a virtual thread this blocking sleep
        // unmounts the carrier, so many in-flight delayed requests share few OS threads.
        int plannedDelay = computeDelay(config);
        int delay = plannedDelay;
        long sleepStart = System.nanoTime();
        try {
            Thread.sleep(plannedDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // We slept less than planned; report the actual elapsed time, not the planned delay.
            delay = (int) Math.min(plannedDelay, (System.nanoTime() - sleepStart) / 1_000_000L);
        }

        // Choose the response status: a configured weighted status distribution wins; otherwise
        // the binary errorRate (200 vs 500). 2xx/3xx is treated as success, >=400 as failure.
        int status = chooseStatus(config);
        recordHistory(request.getMethod(), endpointLabel, headers, requestBody, status, delay);
        if (status >= 200 && status < 400) {
            return handleSuccess(requestId, delay, config, headers, requestParams, requestBody, endpointLabel, status);
        }
        return handleError(requestId, delay, endpointLabel, status);
    }

    /** Computes a delay in [minDelay, maxDelay] using the configured distribution. */
    private int computeDelay(MockEndpointConfig config) {
        int min = config.getMinDelay();
        int max = config.getMaxDelay();
        if (max <= min) {
            return min;
        }
        String dist = config.getDelayDistribution();
        if (dist == null) {
            dist = "uniform";
        }
        switch (dist.toLowerCase()) {
            case "normal": {
                double mean = (min + max) / 2.0;
                double stddev = (max - min) / 6.0; // ~99.7% of mass within [min,max] before clamping
                double v = mean + ThreadLocalRandom.current().nextGaussian() * stddev;
                return (int) Math.max(min, Math.min(max, Math.round(v)));
            }
            case "lognormal": {
                // Right-skewed: most requests near min with an occasional long tail toward max.
                double sample = Math.exp(ThreadLocalRandom.current().nextGaussian() * 0.5); // median 1
                double norm = Math.min(sample / 4.0, 1.0); // squash into [0,1], skewed low
                return (int) Math.max(min, Math.min(max, Math.round(min + norm * (max - min))));
            }
            case "uniform":
            default:
                return ThreadLocalRandom.current().nextInt(min, max + 1);
        }
    }

    /**
     * Chooses an HTTP status code. If a non-empty weighted {@code statusDistribution} is configured
     * it is sampled by weight; otherwise the legacy {@code errorRate} decides between 200 and 500.
     */
    private int chooseStatus(MockEndpointConfig config) {
        Map<String, Integer> dist = config.getStatusDistribution();
        if (dist != null && !dist.isEmpty()) {
            int total = 0;
            for (Integer w : dist.values()) {
                if (w != null && w > 0) {
                    total += w;
                }
            }
            if (total > 0) {
                int r = ThreadLocalRandom.current().nextInt(total);
                int cumulative = 0;
                for (Map.Entry<String, Integer> entry : dist.entrySet()) {
                    Integer w = entry.getValue();
                    if (w == null || w <= 0) {
                        continue;
                    }
                    cumulative += w;
                    if (r < cumulative) {
                        try {
                            return Integer.parseInt(entry.getKey().trim());
                        } catch (NumberFormatException ex) {
                            return HttpStatus.OK.value();
                        }
                    }
                }
            }
        }
        double errorRate = config.getErrorRate();
        if (errorRate > 0.0 && ThreadLocalRandom.current().nextDouble() < errorRate) {
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
        return HttpStatus.OK.value();
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

    private ResponseEntity<Object> handleError(long requestId, int delay, String endpoint, int status) {
        statisticsService.recordFailure();
        statisticsService.recordFailure(endpoint);
        statisticsService.recordProcessingTime(delay);
        statisticsService.recordProcessingTime(delay, endpoint);

        if (logger.isDebugEnabled()) {
            logger.debug("Completed request #{}: Status {} - Response time: {}ms", requestId, status, delay);
        }

        Map<String, Object> response = ApiResponse.error()
                .withMessage("Simulated error")
                .withRequestId(requestId)
                .withProcessingTime(delay)
                .build();

        return ResponseEntity.status(status).body(response);
    }

    private ResponseEntity<Object> handleSuccess(
            long requestId,
            int delay,
            MockEndpointConfig config,
            Map<String, String> headers,
            Map<String, String> requestParams,
            String requestBody,
            String endpoint,
            int status) {

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
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(status);
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
            logger.debug("Completed request #{}: Status {} - Response time: {}ms", requestId, status, delay);
        }

        return responseBuilder.body(response.build());
    }
}
