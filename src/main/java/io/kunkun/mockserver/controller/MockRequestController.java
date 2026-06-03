package io.kunkun.mockserver.controller;

import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.dto.ApiResponse;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import io.kunkun.mockserver.dto.MockRule;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@RestController
public class MockRequestController {

    private static final Logger logger = LoggerFactory.getLogger(MockRequestController.class);

    /** Single bounded bucket for all unconfigured paths, so metric/history cardinality stays bounded. */
    private static final String UNMATCHED_ENDPOINT = "(unmatched)";

    /** Upper bound on padded response size, so a misconfigured responseSizeBytes can't OOM the JVM. */
    private static final int MAX_RESPONSE_BODY_BYTES = 10 * 1024 * 1024;

    /** Truncated/invalid JSON returned by the "malformed" fault mode. */
    private static final String MALFORMED_JSON_BODY = "{\"status\":\"ok\",\"data\":";

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

    /**
     * Per-endpoint served-request counts, for the "degrade after N requests" scenario. Keyed by the
     * bounded endpoint label (configured endpoints + one "(unmatched)" bucket), so it can't grow
     * without bound from caller-controlled paths.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            endpointRequestCounts = new java.util.concurrent.ConcurrentHashMap<>();

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

        // Request-matching rules take precedence over the status/error-rate behavior.
        if (matched != null && config.getRules() != null && !config.getRules().isEmpty()) {
            MockRule rule = matchRule(config.getRules(), request.getMethod(), headers, requestParams, requestBody);
            if (rule != null) {
                int ruleStatus = (rule.getStatus() >= 100 && rule.getStatus() <= 999) ? rule.getStatus() : 200;
                recordHistory(request.getMethod(), endpointLabel, headers, requestBody, ruleStatus, delay);
                return handleRuleResponse(requestId, delay, rule, ruleStatus, endpointLabel);
            }
        }

        // Stateful degradation: after N served requests, switch to the degraded error rate.
        double effectiveErrorRate = config.getErrorRate();
        if (matched != null && config.getDegradeAfterRequests() > 0) {
            long served = endpointRequestCounts
                    .computeIfAbsent(endpointLabel, k -> new java.util.concurrent.atomic.AtomicLong())
                    .incrementAndGet();
            if (served > config.getDegradeAfterRequests()) {
                effectiveErrorRate = config.getDegradedErrorRate();
            }
        }

        // Choose the response status: a configured weighted status distribution wins; otherwise
        // the (possibly degraded) errorRate (200 vs 500). 2xx/3xx is success, >=400 is failure.
        int status = chooseStatus(config, effectiveErrorRate);
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
                // Use long arithmetic so max == Integer.MAX_VALUE doesn't overflow (max + 1).
                return (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1L);
        }
    }

    /**
     * Chooses an HTTP status code. If a non-empty weighted {@code statusDistribution} is configured
     * it is sampled by weight; otherwise the legacy {@code errorRate} decides between 200 and 500.
     */
    private int chooseStatus(MockEndpointConfig config, double effectiveErrorRate) {
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
                            int parsed = Integer.parseInt(entry.getKey().trim());
                            // Only emit codes ResponseEntity/HttpStatusCode accept ([100,999]);
                            // anything else (e.g. "0", "50", "-1") would throw and turn into a 400.
                            return (parsed >= 100 && parsed <= 999) ? parsed : HttpStatus.OK.value();
                        } catch (NumberFormatException ex) {
                            return HttpStatus.OK.value();
                        }
                    }
                }
            }
        }
        if (effectiveErrorRate > 0.0 && ThreadLocalRandom.current().nextDouble() < effectiveErrorRate) {
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

        // Build response with custom headers (framework-managed headers skipped, CR/LF stripped).
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.status(status);
        applyResponseHeaders(responseBuilder, config.getResponseHeaders());

        if (logger.isDebugEnabled()) {
            logger.debug("Completed request #{}: Status {} - Response time: {}ms", requestId, status, delay);
        }

        // Network-level fault injection: with probability faultRate, return an empty or malformed
        // body (status/headers unchanged) so clients can be tested against bad responses.
        String faultMode = config.getFaultMode();
        if (faultMode != null && !"none".equalsIgnoreCase(faultMode)
                && config.getFaultRate() > 0.0
                && ThreadLocalRandom.current().nextDouble() < config.getFaultRate()) {
            String faultBody = "empty".equalsIgnoreCase(faultMode) ? "" : MALFORMED_JSON_BODY;
            return responseBuilder.body(faultBody);
        }

        // Clamp the requested size so a huge/typo'd value can't exhaust the heap on a load target.
        int targetSize = Math.min(config.getResponseSizeBytes(), MAX_RESPONSE_BODY_BYTES);

        // Custom raw body mode (templated) takes precedence over the default JSON envelope.
        if (config.getResponseBody() != null) {
            String body = padToSize(substitutePlaceholders(config.getResponseBody(), requestId), targetSize);
            return responseBuilder.body(body);
        }

        ApiResponse response = ApiResponse.success()
                .withMessage(config.getResponseMessage())
                .withRequestId(requestId)
                .withProcessingTime(delay)
                .with("headers", headers)
                .with("params", requestParams);

        if (requestBody != null && !requestBody.isEmpty()) {
            response.with("requestBody", requestBody);
        }

        // Size control: pad the envelope so the body is at least targetSize bytes.
        if (targetSize > 0) {
            response.with("padding", "x".repeat(targetSize));
        }

        return responseBuilder.body(response.build());
    }

    /** Returns the first rule whose criteria all match the request, or null if none match. */
    private MockRule matchRule(List<MockRule> rules, String method, Map<String, String> headers,
                              Map<String, String> params, String body) {
        for (MockRule rule : rules) {
            if (rule.getMethod() != null && !rule.getMethod().equalsIgnoreCase(method)) {
                continue;
            }
            if (!allMatch(rule.getHeaderMatch(), name -> headerValueIgnoreCase(headers, name))) {
                continue;
            }
            if (!allMatch(rule.getQueryMatch(), params::get)) {
                continue;
            }
            if (rule.getBodyContains() != null && !rule.getBodyContains().isEmpty()
                    && (body == null || !body.contains(rule.getBodyContains()))) {
                continue;
            }
            return rule;
        }
        return null;
    }

    /** True if every required (key,value) is present and equal per the supplied value lookup. */
    private static boolean allMatch(Map<String, String> required, java.util.function.Function<String, String> lookup) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, String> e : required.entrySet()) {
            String actual = lookup.apply(e.getKey());
            if (actual == null || !actual.equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static String headerValueIgnoreCase(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Builds the response for a matched rule (raw body or JSON envelope), recording stats by status. */
    private ResponseEntity<Object> handleRuleResponse(long requestId, int delay, MockRule rule,
                                                      int status, String endpoint) {
        boolean success = status >= 200 && status < 400;
        if (success) {
            statisticsService.recordSuccess();
            statisticsService.recordSuccess(endpoint);
        } else {
            statisticsService.recordFailure();
            statisticsService.recordFailure(endpoint);
        }
        statisticsService.recordProcessingTime(delay);
        statisticsService.recordProcessingTime(delay, endpoint);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        applyResponseHeaders(builder, rule.getResponseHeaders());

        if (rule.getResponseBody() != null) {
            return builder.body(substitutePlaceholders(rule.getResponseBody(), requestId));
        }
        String message = rule.getResponseMessage() != null
                ? rule.getResponseMessage()
                : (success ? "Matched rule" : "Simulated error");
        Map<String, Object> body = (success ? ApiResponse.success() : ApiResponse.error())
                .withMessage(message)
                .withRequestId(requestId)
                .withProcessingTime(delay)
                .build();
        return builder.body(body);
    }

    /** Applies custom response headers, skipping framework-managed ones and stripping CR/LF. */
    private static void applyResponseHeaders(ResponseEntity.BodyBuilder builder, Map<String, Object> headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, Object> header : headers.entrySet()) {
            String name = header.getKey();
            if (name == null || FORBIDDEN_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
                continue;
            }
            String value = String.valueOf(header.getValue()).replace("\r", "").replace("\n", "");
            builder.header(name, value);
        }
    }

    /** Substitutes ${requestId}/${timestamp}/${random} placeholders in a custom response body. */
    private static String substitutePlaceholders(String template, long requestId) {
        if (template.indexOf("${") < 0) {
            return template;
        }
        return template
                .replace("${requestId}", Long.toString(requestId))
                .replace("${timestamp}", Long.toString(System.currentTimeMillis()))
                .replace("${random}", Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE)));
    }

    /** Pads {@code body} with filler so it is at least {@code targetSize} bytes (chars), if set. */
    private static String padToSize(String body, int targetSize) {
        if (targetSize <= 0 || body.length() >= targetSize) {
            return body;
        }
        StringBuilder sb = new StringBuilder(targetSize);
        sb.append(body);
        while (sb.length() < targetSize) {
            sb.append('x');
        }
        return sb.toString();
    }
}
