package io.kunkun.mockserver.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks request statistics and exposes them as Micrometer metrics.
 *
 * Counter semantics: Prometheus counters must be monotonically increasing and must
 * not be reset. FunctionCounter wraps the AtomicLong values so Prometheus sees correct
 * counter semantics (the gauge-based "resettable" counters are kept separately for the
 * admin /stats endpoint which does support reset).
 */
@Service
public class StatisticsService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);

    // Resettable counters — source of truth for /admin/stats and the Gauge metrics below
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);

    // Non-resettable lifetime counter for Prometheus FunctionCounters
    private final AtomicLong lifetimeTotalRequests = new AtomicLong(0);
    private final AtomicLong lifetimeSuccessfulRequests = new AtomicLong(0);
    private final AtomicLong lifetimeFailedRequests = new AtomicLong(0);

    private final AtomicLong requestCounter = new AtomicLong(0);

    private final Timer requestTimer;
    private final MeterRegistry meterRegistry;

    public StatisticsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        // FunctionCounter wraps the lifetime AtomicLongs — monotonically increasing, correct Prometheus semantics
        FunctionCounter.builder("mock_server_requests_total", lifetimeTotalRequests, AtomicLong::doubleValue)
                .description("Total number of mock requests received (lifetime, not reset on /stats/reset)")
                .register(meterRegistry);

        FunctionCounter.builder("mock_server_requests_successful", lifetimeSuccessfulRequests, AtomicLong::doubleValue)
                .description("Number of successful mock requests (lifetime)")
                .register(meterRegistry);

        FunctionCounter.builder("mock_server_requests_failed", lifetimeFailedRequests, AtomicLong::doubleValue)
                .description("Number of failed mock requests (lifetime)")
                .register(meterRegistry);

        // Register timer for request processing
        this.requestTimer = Timer.builder("mock_server_request_duration")
                .description("Time taken to process mock requests")
                .register(meterRegistry);

        // Register gauges for resettable current values (useful for admin dashboards, not Prometheus)
        Gauge.builder("mock_server_success_rate", this, StatisticsService::calculateSuccessRate)
                .description("Current success rate (0.0 to 1.0), resets with /stats/reset")
                .register(meterRegistry);

        Gauge.builder("mock_server_requests_current_total", totalRequests, AtomicLong::get)
                .description("Current total requests (resettable via /stats/reset)")
                .register(meterRegistry);

        Gauge.builder("mock_server_requests_current_successful", successfulRequests, AtomicLong::get)
                .description("Current successful requests (resettable)")
                .register(meterRegistry);

        Gauge.builder("mock_server_requests_current_failed", failedRequests, AtomicLong::get)
                .description("Current failed requests (resettable)")
                .register(meterRegistry);
    }

    public long incrementAndGetRequestId() {
        return requestCounter.incrementAndGet();
    }

    public void recordRequest() {
        totalRequests.incrementAndGet();
        lifetimeTotalRequests.incrementAndGet();
    }

    public void recordSuccess() {
        successfulRequests.incrementAndGet();
        lifetimeSuccessfulRequests.incrementAndGet();
    }

    public void recordFailure() {
        failedRequests.incrementAndGet();
        lifetimeFailedRequests.incrementAndGet();
    }

    /**
     * Records the processing time for a request (global counter).
     * @param processingTimeMs processing time in milliseconds
     */
    public void recordProcessingTime(long processingTimeMs) {
        requestTimer.record(processingTimeMs, TimeUnit.MILLISECONDS);
    }

    // -----------------------------------------------------------------------
    // Per-endpoint Micrometer metrics (overloads — keep global counters intact)
    // -----------------------------------------------------------------------

    /**
     * Increments the per-endpoint request counter in addition to the global counters.
     * Call {@link #recordRequest()} separately to keep the global counters in sync.
     *
     * @param endpoint the request path (used as a Micrometer tag value)
     */
    public void recordRequest(String endpoint) {
        Counter.builder("mock_server_endpoint_requests_total")
                .tag("endpoint", endpoint)
                .description("Requests per endpoint")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increments the per-endpoint success counter.
     * Call {@link #recordSuccess()} separately to keep the global counters in sync.
     *
     * @param endpoint the request path (used as a Micrometer tag value)
     */
    public void recordSuccess(String endpoint) {
        Counter.builder("mock_server_endpoint_requests_total")
                .tag("endpoint", endpoint)
                .tag("result", "success")
                .description("Requests per endpoint")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Increments the per-endpoint failure counter.
     * Call {@link #recordFailure()} separately to keep the global counters in sync.
     *
     * @param endpoint the request path (used as a Micrometer tag value)
     */
    public void recordFailure(String endpoint) {
        Counter.builder("mock_server_endpoint_requests_total")
                .tag("endpoint", endpoint)
                .tag("result", "failure")
                .description("Requests per endpoint")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Records per-endpoint request latency.
     * Call {@link #recordProcessingTime(long)} separately to keep the global timer in sync.
     *
     * @param processingTimeMs processing time in milliseconds
     * @param endpoint         the request path (used as a Micrometer tag value)
     */
    public void recordProcessingTime(long processingTimeMs, String endpoint) {
        Timer.builder("mock_server_endpoint_request_duration")
                .tag("endpoint", endpoint)
                .description("Request duration per endpoint")
                .register(meterRegistry)
                .record(processingTimeMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns raw statistics as a domain map.
     * The controller is responsible for wrapping this in an ApiResponse.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRequests", totalRequests.get());
        stats.put("successfulRequests", successfulRequests.get());
        stats.put("failedRequests", failedRequests.get());
        stats.put("successRate", calculateSuccessRate());
        return stats;
    }

    public void reset() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        // Note: lifetime counters and FunctionCounters are intentionally NOT reset —
        // Prometheus counters must be monotonically increasing.
    }

    /**
     * Calculates success rate as a decimal (0.0 to 1.0).
     * Based on the resettable counters.
     */
    public double calculateSuccessRate() {
        long total = totalRequests.get();
        return total > 0 ? (double) successfulRequests.get() / total : 0.0;
    }

    /**
     * Calculates success rate as a formatted percentage string.
     */
    public String calculateSuccessRatePercentage() {
        return String.format("%.2f", calculateSuccessRate() * 100.0);
    }

    @Scheduled(fixedRateString = "${mock-server.stats-log-interval-ms:10000}")
    public void logStats() {
        logger.info("STATS - Total: {} | Success: {} | Failed: {} | Rate: {}%",
                totalRequests.get(),
                successfulRequests.get(),
                failedRequests.get(),
                calculateSuccessRatePercentage());
    }
}
