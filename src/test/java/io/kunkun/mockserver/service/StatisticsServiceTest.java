package io.kunkun.mockserver.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsServiceTest {

    private StatisticsService service;

    @BeforeEach
    void setUp() {
        service = new StatisticsService(new SimpleMeterRegistry());
    }

    // ========== Basic Statistics Tests ==========

    @Test
    void initialStats_areZero() {
        Map<String, Object> stats = service.getStats();

        assertEquals(0L, stats.get("totalRequests"));
        assertEquals(0L, stats.get("successfulRequests"));
        assertEquals(0L, stats.get("failedRequests"));
        assertEquals(0.0, stats.get("successRate"));
    }

    @Test
    void recordRequest_incrementsTotalRequests() {
        service.recordRequest();
        service.recordRequest();
        service.recordRequest();

        Map<String, Object> stats = service.getStats();
        assertEquals(3L, stats.get("totalRequests"));
    }

    @Test
    void recordSuccess_incrementsSuccessfulRequests() {
        service.recordSuccess();
        service.recordSuccess();

        Map<String, Object> stats = service.getStats();
        assertEquals(2L, stats.get("successfulRequests"));
    }

    @Test
    void recordFailure_incrementsFailedRequests() {
        service.recordFailure();

        Map<String, Object> stats = service.getStats();
        assertEquals(1L, stats.get("failedRequests"));
    }

    @Test
    void reset_clearsAllStats() {
        service.recordRequest();
        service.recordRequest();
        service.recordSuccess();
        service.recordFailure();

        service.reset();

        Map<String, Object> stats = service.getStats();
        assertEquals(0L, stats.get("totalRequests"));
        assertEquals(0L, stats.get("successfulRequests"));
        assertEquals(0L, stats.get("failedRequests"));
    }

    // ========== Request ID Tests ==========

    @Test
    void incrementAndGetRequestId_returnsIncreasingIds() {
        long id1 = service.incrementAndGetRequestId();
        long id2 = service.incrementAndGetRequestId();
        long id3 = service.incrementAndGetRequestId();

        assertEquals(1, id1);
        assertEquals(2, id2);
        assertEquals(3, id3);
    }

    // ========== Success Rate Tests ==========

    @Test
    void calculateSuccessRate_withNoRequests_returnsZero() {
        assertEquals(0.0, service.calculateSuccessRate());
    }

    @Test
    void calculateSuccessRate_withAllSuccesses_returnsOne() {
        service.recordRequest();
        service.recordSuccess();
        service.recordRequest();
        service.recordSuccess();

        assertEquals(1.0, service.calculateSuccessRate());
    }

    @Test
    void calculateSuccessRate_withAllFailures_returnsZero() {
        service.recordRequest();
        service.recordFailure();
        service.recordRequest();
        service.recordFailure();

        assertEquals(0.0, service.calculateSuccessRate());
    }

    @Test
    void calculateSuccessRate_withMixedResults_returnsCorrectRate() {
        // 3 successes, 1 failure = 75% success rate
        service.recordRequest();
        service.recordSuccess();
        service.recordRequest();
        service.recordSuccess();
        service.recordRequest();
        service.recordSuccess();
        service.recordRequest();
        service.recordFailure();

        assertEquals(0.75, service.calculateSuccessRate());
    }

    @Test
    void calculateSuccessRatePercentage_formatsCorrectly() {
        service.recordRequest();
        service.recordSuccess();
        service.recordRequest();
        service.recordSuccess();
        service.recordRequest();
        service.recordFailure();

        // 2/3 = 66.67%
        String percentage = service.calculateSuccessRatePercentage();
        assertEquals("66.67", percentage);
    }

    // ========== Thread Safety Tests ==========

    @Test
    void statistics_areConcurrentlySafe() throws InterruptedException {
        int threadCount = 10;
        int iterationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        service.recordRequest();
                        if (j % 2 == 0) {
                            service.recordSuccess();
                        } else {
                            service.recordFailure();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        Map<String, Object> stats = service.getStats();
        long total = (Long) stats.get("totalRequests");
        long successes = (Long) stats.get("successfulRequests");
        long failures = (Long) stats.get("failedRequests");

        assertEquals(threadCount * iterationsPerThread, total);
        assertEquals(successes + failures, total);
        assertEquals(0.5, service.calculateSuccessRate(), 0.01); // ~50% success rate
    }

    @Test
    void requestId_isConcurrentlySafe() throws InterruptedException {
        int threadCount = 10;
        int iterationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        service.incrementAndGetRequestId();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Next ID should be total + 1
        long nextId = service.incrementAndGetRequestId();
        assertEquals(threadCount * iterationsPerThread + 1, nextId);
    }
}
