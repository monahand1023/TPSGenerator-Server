package io.kunkun.mockserver.health;

import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.service.MockEndpointService;
import io.kunkun.mockserver.service.StatisticsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MockServerHealthIndicator.
 *
 * Uses real (but minimally configured) collaborators rather than Mockito mocks so the tests
 * are not affected by Mockito's inline-mock limitations on newer JVMs.
 */
class MockServerHealthIndicatorTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private MockServerProperties defaultProperties() {
        MockServerProperties p = new MockServerProperties();
        p.setDefaultMinDelay(10);
        p.setDefaultMaxDelay(100);
        p.setDefaultErrorRate(0.0);
        return p;
    }

    /**
     * Build a health indicator whose StatisticsService has a specific success rate.
     *
     * We record (total) requests and (successful) requests to produce the desired rate.
     * total=100, success=successCount gives successRate = successCount / 100.
     */
    private MockServerHealthIndicator buildIndicatorWithRate(double successRate, int endpointCount) {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        StatisticsService stats = new StatisticsService(meterRegistry);

        // Pump the counters to obtain the desired success rate
        int total = 100;
        int successful = (int) Math.round(successRate * total);
        for (int i = 0; i < total; i++) {
            stats.recordRequest();
        }
        for (int i = 0; i < successful; i++) {
            stats.recordSuccess();
        }

        MockServerProperties properties = defaultProperties();
        MockEndpointService endpointService = new MockEndpointService(properties);

        // Add placeholder endpoint configs to reach the desired count
        for (int i = 0; i < endpointCount; i++) {
            endpointService.configureEndpoint(
                    "endpoint-" + i,
                    new io.kunkun.mockserver.dto.MockEndpointConfig(
                            0, 10, 0.0, new java.util.HashMap<>(), "test"));
        }

        return new MockServerHealthIndicator(stats, endpointService);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void health_isUpWhenSuccessRateIsHealthy() {
        Health health = buildIndicatorWithRate(0.95, 5).health();
        assertEquals(Status.UP, health.getStatus());
    }

    @Test
    void health_isUpWhenSuccessRateIsExactlyAtThreshold() {
        // successRate = 0.10 → error rate = 0.90, equals (does NOT exceed) threshold → UP
        // Condition for DEGRADED: successRate < (1 - 0.9) = 0.1
        Health health = buildIndicatorWithRate(0.10, 0).health();
        assertEquals(Status.UP, health.getStatus());
    }

    @Test
    void health_isDegradedWhenErrorRateExceedsThreshold() {
        // successRate = 0.05 → error rate = 0.95 > 90% → DEGRADED
        Health health = buildIndicatorWithRate(0.05, 3).health();
        assertEquals("DEGRADED", health.getStatus().getCode());
    }

    @Test
    void health_detailsIncludeSuccessRate() {
        Health health = buildIndicatorWithRate(0.80, 2).health();
        assertNotNull(health.getDetails());
        assertTrue(health.getDetails().containsKey("successRate"),
                "Expected 'successRate' in details: " + health.getDetails().keySet());
    }

    @Test
    void health_detailsIncludeConfiguredEndpoints() {
        Health health = buildIndicatorWithRate(0.90, 7).health();
        assertEquals(7, health.getDetails().get("configuredEndpoints"));
    }

    @Test
    void health_degradedDetailsIncludeWarning() {
        Health health = buildIndicatorWithRate(0.05, 0).health();
        assertEquals("DEGRADED", health.getStatus().getCode());
        assertTrue(health.getDetails().containsKey("warning"),
                "Expected 'warning' in DEGRADED details: " + health.getDetails().keySet());
    }

    @Test
    void health_upDetailsIncludeStatusMessage() {
        Health health = buildIndicatorWithRate(1.0, 1).health();
        assertEquals(Status.UP, health.getStatus());
        assertTrue(health.getDetails().containsKey("status"),
                "Expected 'status' in UP details: " + health.getDetails().keySet());
    }

    @Test
    void health_noRequests_isUp_notDegraded() {
        // With zero traffic, successRate is 0.0 but that is NOT a 100% error rate — a freshly
        // started server must report UP, not DEGRADED (the error-rate check is gated on total>0).
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        StatisticsService stats = new StatisticsService(meterRegistry);
        MockEndpointService endpoints = new MockEndpointService(defaultProperties());

        MockServerHealthIndicator indicator = new MockServerHealthIndicator(stats, endpoints);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
    }
}
