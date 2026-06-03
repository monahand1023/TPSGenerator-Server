package io.kunkun.mockserver.service;

import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.dto.ApiResponse;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Record/replay proxy. Forwards a request to the configured upstream, returns the upstream's
 * response to the caller, and (when recording) captures it as a mock endpoint so future requests
 * to the same path replay the captured response without contacting the upstream.
 */
@Service
public class ProxyService {

    private static final Logger logger = LoggerFactory.getLogger(ProxyService.class);

    /** Headers the JDK HttpClient forbids setting (it throws), plus hop-by-hop ones. */
    private static final Set<String> RESTRICTED_REQUEST_HEADERS = Set.of(
            "host", "content-length", "connection", "upgrade", "expect",
            "transfer-encoding", "keep-alive");

    private final MockServerProperties properties;
    private final MockEndpointService endpointService;
    private final StatisticsService statisticsService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ProxyService(MockServerProperties properties,
                        MockEndpointService endpointService,
                        StatisticsService statisticsService) {
        this.properties = properties;
        this.endpointService = endpointService;
        this.statisticsService = statisticsService;
    }

    /** True when proxying is enabled and an upstream URL is configured. */
    public boolean isEnabled() {
        MockServerProperties.Proxy p = properties.getProxy();
        return p.isEnabled() && p.getUpstreamUrl() != null && !p.getUpstreamUrl().isBlank();
    }

    /**
     * Forwards the request upstream, records stats + (optionally) a replayable mock, and returns
     * the upstream response (or 502 on failure).
     *
     * @param request       the incoming request
     * @param body          the request body (may be null/empty)
     * @param headers       the incoming headers
     * @param endpointLabel bounded label for per-endpoint stats
     */
    public ResponseEntity<Object> handle(HttpServletRequest request, String body,
                                         Map<String, String> headers, String endpointLabel) {
        String base = stripTrailingSlash(properties.getProxy().getUpstreamUrl());
        String target = base + request.getRequestURI()
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "");

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(30))
                .method(request.getMethod(),
                        (body == null || body.isEmpty())
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body));
        for (Map.Entry<String, String> h : headers.entrySet()) {
            String name = h.getKey();
            if (name == null || RESTRICTED_REQUEST_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
                continue;
            }
            builder.header(name, h.getValue());
        }

        long start = System.nanoTime();
        try {
            HttpResponse<String> upstream = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - start) / 1_000_000L);

            boolean success = upstream.statusCode() >= 200 && upstream.statusCode() < 400;
            if (success) {
                statisticsService.recordSuccess();
                statisticsService.recordSuccess(endpointLabel);
            } else {
                statisticsService.recordFailure();
                statisticsService.recordFailure(endpointLabel);
            }
            statisticsService.recordProcessingTime(latencyMs);
            statisticsService.recordProcessingTime(latencyMs, endpointLabel);

            String contentType = upstream.headers().firstValue("Content-Type").orElse(null);

            if (properties.getProxy().isRecord()) {
                MockEndpointConfig recorded = buildRecordedConfig(
                        upstream.statusCode(), contentType, upstream.body(), latencyMs);
                endpointService.configureEndpoint(request.getRequestURI(), recorded);
                logger.info("Recorded upstream response for '{}' (status {}, {} ms)",
                        request.getRequestURI(), upstream.statusCode(), latencyMs);
            }

            ResponseEntity.BodyBuilder out = ResponseEntity.status(upstream.statusCode());
            if (contentType != null) {
                out.header("Content-Type", contentType);
            }
            return out.body(upstream.body());

        } catch (Exception e) {
            statisticsService.recordFailure();
            statisticsService.recordFailure(endpointLabel);
            logger.warn("Proxy to '{}' failed: {}", target, e.getMessage());
            return ResponseEntity.status(502).body(
                    ApiResponse.error().withMessage("Upstream proxy error: " + e.getMessage()).build());
        }
    }

    /**
     * Builds a mock config that replays a captured upstream response: same body, same status (via a
     * single-entry status distribution), the captured Content-Type, and a fixed delay equal to the
     * observed upstream latency. Package-private for testing.
     */
    static MockEndpointConfig buildRecordedConfig(int status, String contentType, String body, int latencyMs) {
        Map<String, Object> responseHeaders = new HashMap<>();
        if (contentType != null) {
            responseHeaders.put("Content-Type", contentType);
        }
        MockEndpointConfig config = new MockEndpointConfig(
                latencyMs, latencyMs, 0.0, responseHeaders, null);
        config.setResponseBody(body != null ? body : "");
        config.setStatusDistribution(Map.of(String.valueOf(status), 100));
        return config;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
