package io.kunkun.mockserver.health;

import io.kunkun.mockserver.service.MockEndpointService;
import io.kunkun.mockserver.service.StatisticsService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator for the mock server.
 * Reports health status along with key metrics.
 */
@Component
public class MockServerHealthIndicator implements HealthIndicator {

    private final StatisticsService statisticsService;
    private final MockEndpointService endpointService;

    // Threshold for considering the server unhealthy based on error rate
    private static final double UNHEALTHY_ERROR_RATE_THRESHOLD = 0.9;

    public MockServerHealthIndicator(StatisticsService statisticsService, MockEndpointService endpointService) {
        this.statisticsService = statisticsService;
        this.endpointService = endpointService;
    }

    @Override
    public Health health() {
        long totalRequests = statisticsService.getTotalRequests();
        double successRate = statisticsService.calculateSuccessRate();
        int configuredEndpoints = endpointService.getConfiguredEndpointCount();

        Health.Builder builder = Health.up()
                .withDetail("successRate", String.format("%.2f%%", successRate * 100))
                .withDetail("configuredEndpoints", configuredEndpoints)
                .withDetail("status", "Mock server is operational");

        // Only evaluate the error-rate threshold once there is traffic. With zero requests the
        // success rate is 0.0, which must NOT be treated as a 100% error rate (a fresh server is UP).
        if (totalRequests > 0 && successRate < (1 - UNHEALTHY_ERROR_RATE_THRESHOLD)) {
            builder = Health.status("DEGRADED")
                    .withDetail("successRate", String.format("%.2f%%", successRate * 100))
                    .withDetail("configuredEndpoints", configuredEndpoints)
                    .withDetail("warning", "High simulated error rate detected");
        }

        return builder.build();
    }
}
