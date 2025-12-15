package io.kunkun.mockserver.service;

import io.kunkun.mockserver.dto.ApiResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class StatisticsService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong requestCounter = new AtomicLong(0);

    // Micrometer metrics
    private final Counter totalRequestsCounter;
    private final Counter successfulRequestsCounter;
    private final Counter failedRequestsCounter;
    private final Timer requestTimer;

    public StatisticsService(MeterRegistry meterRegistry) {
        // Register counters
        this.totalRequestsCounter = Counter.builder("mock_server_requests_total")
                .description("Total number of mock requests received")
                .register(meterRegistry);

        this.successfulRequestsCounter = Counter.builder("mock_server_requests_successful")
                .description("Number of successful mock requests")
                .register(meterRegistry);

        this.failedRequestsCounter = Counter.builder("mock_server_requests_failed")
                .description("Number of failed mock requests (simulated errors)")
                .register(meterRegistry);

        // Register timer for request processing
        this.requestTimer = Timer.builder("mock_server_request_duration")
                .description("Time taken to process mock requests")
                .register(meterRegistry);

        // Register gauges for current values
        Gauge.builder("mock_server_success_rate", this, StatisticsService::calculateSuccessRate)
                .description("Current success rate (0.0 to 1.0)")
                .register(meterRegistry);

        Gauge.builder("mock_server_requests_current_total", totalRequests, AtomicLong::get)
                .description("Current total requests (resettable)")
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
        totalRequestsCounter.increment();
    }

    public void recordSuccess() {
        successfulRequests.incrementAndGet();
        successfulRequestsCounter.increment();
    }

    public void recordFailure() {
        failedRequests.incrementAndGet();
        failedRequestsCounter.increment();
    }

    /**
     * Records the processing time for a request.
     * @param processingTimeMs processing time in milliseconds
     */
    public void recordProcessingTime(long processingTimeMs) {
        requestTimer.record(processingTimeMs, TimeUnit.MILLISECONDS);
    }

    public Map<String, Object> getStats() {
        return ApiResponse.success()
                .with("totalRequests", totalRequests.get())
                .with("successfulRequests", successfulRequests.get())
                .with("failedRequests", failedRequests.get())
                .with("successRate", calculateSuccessRate())
                .build();
    }

    public void reset() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        // Note: Micrometer counters are not reset as they track lifetime totals
        // The gauges will reflect the reset values
    }

    /**
     * Calculates success rate as a decimal (0.0 to 1.0).
     * Consolidates duplicated calculation logic.
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
