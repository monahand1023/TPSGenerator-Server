package io.kunkun.mockserver.config;

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
        double successRate = statisticsService.calculateSuccessRate();
        int configuredEndpoints = endpointService.getConfiguredEndpointCount();

        Health.Builder builder = Health.up()
                .withDetail("successRate", String.format("%.2f%%", successRate * 100))
                .withDetail("configuredEndpoints", configuredEndpoints)
                .withDetail("status", "Mock server is operational");

        // If error rate is extremely high (>90%), report as degraded
        // This would indicate intentional high error rate configuration
        if (successRate < (1 - UNHEALTHY_ERROR_RATE_THRESHOLD)) {
            builder = Health.status("DEGRADED")
                    .withDetail("successRate", String.format("%.2f%%", successRate * 100))
                    .withDetail("configuredEndpoints", configuredEndpoints)
                    .withDetail("warning", "High simulated error rate detected");
        }

        return builder.build();
    }
}
