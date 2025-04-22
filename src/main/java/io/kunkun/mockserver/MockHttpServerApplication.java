package io.kunkun.mockserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@EnableScheduling
@SpringBootApplication
public class MockHttpServerApplication {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MockController.class);
    public static void main(String[] args) {
        SpringApplication.run(MockHttpServerApplication.class, args);
    }

    @RestController
    public static class MockController {
        private final Random random = new Random();
        private final AtomicLong requestCounter = new AtomicLong(0);
        private final ConcurrentHashMap<String, MockEndpointConfig> endpointConfigs = new ConcurrentHashMap<>();

        // Default configuration
        private int defaultMinDelay = 10;
        private int defaultMaxDelay = 100;
        private double defaultErrorRate = 0.0;

        // Statistics
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong successfulRequests = new AtomicLong(0);
        private final AtomicLong failedRequests = new AtomicLong(0);

        // Configure an endpoint
        @PostMapping("/admin/config/{path}")
        public ResponseEntity<Map<String, Object>> configureEndpoint(
                @PathVariable String path,
                @RequestBody MockEndpointConfig config) {

            endpointConfigs.put(path, config);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Endpoint configured: /" + path);
            response.put("config", config);

            return ResponseEntity.ok(response);
        }

        // Get endpoint configuration
        @GetMapping("/admin/config/{path}")
        public ResponseEntity<MockEndpointConfig> getEndpointConfig(@PathVariable String path) {
            MockEndpointConfig config = endpointConfigs.get(path);
            if (config == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(config);
        }

        // Configure default settings
        @PostMapping("/admin/defaults")
        public ResponseEntity<Map<String, Object>> configureDefaults(
                @RequestParam(required = false) Integer minDelay,
                @RequestParam(required = false) Integer maxDelay,
                @RequestParam(required = false) Double errorRate) {

            if (minDelay != null) defaultMinDelay = minDelay;
            if (maxDelay != null) defaultMaxDelay = maxDelay;
            if (errorRate != null) defaultErrorRate = errorRate;

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("defaultMinDelay", defaultMinDelay);
            response.put("defaultMaxDelay", defaultMaxDelay);
            response.put("defaultErrorRate", defaultErrorRate);

            return ResponseEntity.ok(response);
        }

        // Get server statistics
        @GetMapping("/admin/stats")
        public ResponseEntity<Map<String, Object>> getStats() {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalRequests", totalRequests.get());
            stats.put("successfulRequests", successfulRequests.get());
            stats.put("failedRequests", failedRequests.get());
            stats.put("successRate", totalRequests.get() > 0 ?
                    (double)successfulRequests.get() / totalRequests.get() : 0);

            return ResponseEntity.ok(stats);
        }

        // Reset statistics
        @PostMapping("/admin/stats/reset")
        public ResponseEntity<Map<String, Object>> resetStats() {
            totalRequests.set(0);
            successfulRequests.set(0);
            failedRequests.set(0);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Statistics reset");

            return ResponseEntity.ok(response);
        }

        @Scheduled(fixedRate = 10000) // every 10 seconds
        public void logStats() {
            logger.info("STATS - Total: {} | Success: {} | Failed: {} | Rate: {}%",
                    totalRequests.get(),
                    successfulRequests.get(),
                    failedRequests.get(),
                    totalRequests.get() > 0 ?
                            String.format("%.2f", 100.0 * successfulRequests.get() / totalRequests.get()) :
                            "0.00");
        }

        // Handle all requests to configure paths
        @RequestMapping("/{path}/**")
        public ResponseEntity<Object> handleRequest(
                @PathVariable String path,
                @RequestBody(required = false) String requestBody,
                @RequestParam Map<String, String> requestParams,
                @RequestHeader Map<String, String> headers,
                HttpServletRequest requestServletRequest) {

            // Record request
            long requestId = requestCounter.incrementAndGet();
            totalRequests.incrementAndGet();

            // Log the incoming request
            logger.info("Received request #{}: {} {} - Headers: {}",
                    requestId,
                    requestServletRequest.getMethod(),
                    requestServletRequest.getRequestURI(),
                    headers);

            // Get endpoint config or use defaults
            MockEndpointConfig config = endpointConfigs.getOrDefault(path,
                    new MockEndpointConfig(defaultMinDelay, defaultMaxDelay, defaultErrorRate,
                            new HashMap<>(), "Default response"));

            // Apply delay
            int delay = random.nextInt(config.getMaxDelay() - config.getMinDelay() + 1) + config.getMinDelay();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Check if should return error
            boolean isError = random.nextDouble() < config.getErrorRate();
            if (isError) {
                failedRequests.incrementAndGet();
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("status", "error");
                errorResponse.put("message", "Simulated error");
                errorResponse.put("requestId", requestId);
                errorResponse.put("processingTime", delay);

                logger.info("Completed request #{}: Status 500 - Response time: {}ms",
                        requestId, delay);

                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
            }

            // Return success response
            successfulRequests.incrementAndGet();
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", config.getResponseMessage());
            response.put("requestId", requestId);
            response.put("processingTime", delay);
            response.put("headers", headers);
            response.put("params", requestParams);

            if (requestBody != null && !requestBody.isEmpty()) {
                response.put("requestBody", requestBody);
            }

            // Add custom headers
            ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
            for (Map.Entry<String, Object> header : config.getResponseHeaders().entrySet()) {
                responseBuilder.header(header.getKey(), header.getValue().toString());
            }

            logger.info("Completed request #{}: Status 200 - Response time: {}ms",
                    requestId, delay);

            return responseBuilder.body(response);
        }
    }

    public static class MockEndpointConfig {
        private int minDelay;
        private int maxDelay;
        private double errorRate;
        private Map<String, Object> responseHeaders;
        private String responseMessage;

        public MockEndpointConfig() {
        }

        public MockEndpointConfig(int minDelay, int maxDelay, double errorRate,
                                  Map<String, Object> responseHeaders, String responseMessage) {
            this.minDelay = minDelay;
            this.maxDelay = maxDelay;
            this.errorRate = errorRate;
            this.responseHeaders = responseHeaders;
            this.responseMessage = responseMessage;
        }

        // Getters and setters
        public int getMinDelay() { return minDelay; }
        public void setMinDelay(int minDelay) { this.minDelay = minDelay; }

        public int getMaxDelay() { return maxDelay; }
        public void setMaxDelay(int maxDelay) { this.maxDelay = maxDelay; }

        public double getErrorRate() { return errorRate; }
        public void setErrorRate(double errorRate) { this.errorRate = errorRate; }

        public Map<String, Object> getResponseHeaders() { return responseHeaders; }
        public void setResponseHeaders(Map<String, Object> responseHeaders) { this.responseHeaders = responseHeaders; }

        public String getResponseMessage() { return responseMessage; }
        public void setResponseMessage(String responseMessage) { this.responseMessage = responseMessage; }
    }
}