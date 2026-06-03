package io.kunkun.mockserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import io.kunkun.mockserver.service.MockEndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Admin setup/config calls below hit /admin/** which is now authenticated; run the whole
// class as an authenticated admin. Mock endpoint calls are permitAll, so this is harmless.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "testadmin", roles = "ADMIN")
class MockRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockEndpointService endpointService;

    @BeforeEach
    void setUp() throws Exception {
        // Reset stats and set fast defaults for testing
        mockMvc.perform(post("/admin/stats/reset"));
        mockMvc.perform(post("/admin/defaults")
                .param("minDelay", "0")
                .param("maxDelay", "1")
                .param("errorRate", "0.0"));
    }

    // ========== Basic Request Handling Tests ==========

    @Test
    void handleRequest_withGetMethod_returnsSuccess() throws Exception {
        mockMvc.perform(get("/test-path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.requestId").isNumber())
                .andExpect(jsonPath("$.processingTime").isNumber())
                .andExpect(jsonPath("$.message").value("Default response"));
    }

    @Test
    void handleRequest_withPostMethod_returnsSuccess() throws Exception {
        mockMvc.perform(post("/test-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\": \"value\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.requestBody").value("{\"key\": \"value\"}"));
    }

    @Test
    void handleRequest_withPutMethod_returnsSuccess() throws Exception {
        mockMvc.perform(put("/test-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"update\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void handleRequest_withDeleteMethod_returnsSuccess() throws Exception {
        mockMvc.perform(delete("/test-path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    // ========== Path Handling Tests ==========

    @Test
    void handleRequest_withNestedPath_usesFullPath() throws Exception {
        // Configure a nested path directly via the service (AdminController only
        // supports single-segment {path} variables; multi-segment paths must be
        // seeded programmatically in tests).
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "Nested response");
        endpointService.configureEndpoint("api/v1/users", config);

        // Request the nested path
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Nested response"));
    }

    @Test
    void handleRequest_withTrailingSlash_matchesConfiguredPath() throws Exception {
        // Configure without trailing slash (seed directly — path contains a slash)
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "Trailing slash test");
        endpointService.configureEndpoint("api/endpoint", config);

        // Request with trailing slash should match thanks to path normalization
        mockMvc.perform(get("/api/endpoint/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Trailing slash test"));
    }

    @Test
    void handleRequest_caseInsensitive_matchesConfiguredPath() throws Exception {
        // Configure with lowercase (seed directly — path contains a slash)
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "Case insensitive test");
        endpointService.configureEndpoint("api/casepath", config);

        // Request with different case should match thanks to path normalization
        mockMvc.perform(get("/API/CasePath"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Case insensitive test"));

        mockMvc.perform(get("/Api/CASEPATH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Case insensitive test"));
    }

    @Test
    void handleRequest_withQueryParams_includesParamsInResponse() throws Exception {
        mockMvc.perform(get("/test-path")
                        .param("foo", "bar")
                        .param("count", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params.foo").value("bar"))
                .andExpect(jsonPath("$.params.count").value("10"));
    }

    @Test
    void handleRequest_withHeaders_includesHeadersInResponse() throws Exception {
        // @RequestHeader Map<String,String> preserves header name case as sent by the client.
        // Spring MockMvc sends "x-custom-header" in lowercase, so use the exact key.
        mockMvc.perform(get("/test-path")
                        .header("x-custom-header", "custom-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headers.x-custom-header").value("custom-value"));
    }

    // ========== Configured Endpoint Tests ==========

    @Test
    void handleRequest_withConfiguredEndpoint_usesConfig() throws Exception {
        // Configure endpoint with custom response
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "Custom message");

        mockMvc.perform(post("/admin/config/custom-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        // Verify custom response is used
        mockMvc.perform(get("/custom-endpoint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Custom message"));
    }

    @Test
    void handleRequest_withConfiguredHeaders_addsCustomHeaders() throws Exception {
        // Configure endpoint with custom response headers
        Map<String, Object> responseHeaders = new HashMap<>();
        responseHeaders.put("X-Custom-Response", "header-value");
        responseHeaders.put("X-API-Version", "1.0");

        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, responseHeaders, "Response with headers");

        mockMvc.perform(post("/admin/config/headers-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        // Verify custom headers are added to response
        mockMvc.perform(get("/headers-endpoint"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Custom-Response", "header-value"))
                .andExpect(header().string("X-API-Version", "1.0"));
    }

    // ========== Error Simulation Tests ==========

    @Test
    void handleRequest_with100PercentErrorRate_returnsError() throws Exception {
        // Configure endpoint with 100% error rate
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 1.0, new HashMap<>(), "Should not see this");

        mockMvc.perform(post("/admin/config/error-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        // Request should always return error
        mockMvc.perform(get("/error-endpoint"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Simulated error"))
                .andExpect(jsonPath("$.requestId").isNumber())
                .andExpect(jsonPath("$.processingTime").isNumber());
    }

    @Test
    void handleRequest_with0PercentErrorRate_alwaysSucceeds() throws Exception {
        // Configure endpoint with 0% error rate
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "Always succeeds");

        mockMvc.perform(post("/admin/config/success-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        // Make multiple requests - all should succeed
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/success-endpoint"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"));
        }
    }

    // ========== Status Distribution + Latency Distribution Tests ==========

    @Test
    void handleRequest_withStatusDistribution_returnsConfiguredErrorStatus() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "dist");
        config.setStatusDistribution(Map.of("503", 100)); // 100% weight → always 503

        mockMvc.perform(post("/admin/config/dist-error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/dist-error"))
                .andExpect(status().is(503))
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void handleRequest_withStatusDistribution_returnsConfigured2xxAsSuccess() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "created");
        config.setStatusDistribution(Map.of("201", 100)); // 100% weight → always 201

        mockMvc.perform(post("/admin/config/dist-created")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/dist-created"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("created"));
    }

    @Test
    void handleRequest_withNormalDelayDistribution_staysWithinBounds() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(0, 5, 0.0, new HashMap<>(), "normal");
        config.setDelayDistribution("normal");

        mockMvc.perform(post("/admin/config/normal-delay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/normal-delay"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.processingTime", greaterThanOrEqualTo(0)))
                    .andExpect(jsonPath("$.processingTime", lessThanOrEqualTo(5)));
        }
    }

    @Test
    void handleRequest_withInvalidStatusDistributionKey_fallsBackToSuccess() throws Exception {
        // A key like "50" is not a valid HTTP status; it must NOT blow up into a 400 — it
        // falls back to 200 (regression for the ResponseEntity.status(invalid) crash).
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "x");
        config.setStatusDistribution(Map.of("50", 100));

        mockMvc.perform(post("/admin/config/bad-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/bad-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }

    // ========== Custom Response Body + Size Tests ==========

    @Test
    void handleRequest_withCustomResponseBody_returnsTemplatedBody() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "ignored");
        config.setResponseBody("id=${requestId}");

        mockMvc.perform(post("/admin/config/raw-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/raw-body"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern("id=\\d+")));
    }

    @Test
    void handleRequest_withResponseSizeBytes_padsResponse() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 0.0, new HashMap<>(), "ignored");
        config.setResponseBody("x");
        config.setResponseSizeBytes(256);

        mockMvc.perform(post("/admin/config/big-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        MvcResult res = mockMvc.perform(get("/big-body"))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(res.getResponse().getContentAsString().length() >= 256,
                "body should be padded to at least 256 bytes");
    }

    // ========== Statistics Integration Tests ==========

    @Test
    void handleRequest_incrementsStatistics() throws Exception {
        // Reset stats
        mockMvc.perform(post("/admin/stats/reset"));

        // Make 5 requests
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/stats-test"));
        }

        // Verify stats
        mockMvc.perform(get("/admin/stats"))
                .andExpect(jsonPath("$.totalRequests").value(5))
                .andExpect(jsonPath("$.successfulRequests").value(5))
                .andExpect(jsonPath("$.failedRequests").value(0))
                .andExpect(jsonPath("$.successRate").value(1.0));
    }

    @Test
    void handleRequest_withErrors_tracksFailedRequests() throws Exception {
        // Reset stats
        mockMvc.perform(post("/admin/stats/reset"));

        // Configure endpoint with 100% error rate
        MockEndpointConfig config = new MockEndpointConfig(0, 1, 1.0, new HashMap<>(), "Error");

        mockMvc.perform(post("/admin/config/fail-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)));

        // Make 3 failing requests
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/fail-endpoint"));
        }

        // Verify stats
        mockMvc.perform(get("/admin/stats"))
                .andExpect(jsonPath("$.totalRequests").value(3))
                .andExpect(jsonPath("$.successfulRequests").value(0))
                .andExpect(jsonPath("$.failedRequests").value(3))
                .andExpect(jsonPath("$.successRate").value(0.0));
    }

    // ========== Delay Tests ==========

    @Test
    void handleRequest_respectsDelayConfiguration() throws Exception {
        // Configure endpoint with measurable delay
        MockEndpointConfig config = new MockEndpointConfig(50, 50, 0.0, new HashMap<>(), "Delayed response");

        mockMvc.perform(post("/admin/config/delayed-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        // Time the request
        long startTime = System.currentTimeMillis();
        mockMvc.perform(get("/delayed-endpoint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingTime").value(50));
        long endTime = System.currentTimeMillis();

        // Verify delay was applied (with some tolerance)
        assertTrue(endTime - startTime >= 45, "Request should take at least 45ms");
    }

    // ========== Request ID Tests ==========

    @Test
    void handleRequest_generatesUniqueRequestIds() throws Exception {
        MvcResult result1 = mockMvc.perform(get("/test-path"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult result2 = mockMvc.perform(get("/test-path"))
                .andExpect(status().isOk())
                .andReturn();

        // Parse request IDs
        Map<String, Object> response1 = objectMapper.readValue(
                result1.getResponse().getContentAsString(), Map.class);
        Map<String, Object> response2 = objectMapper.readValue(
                result2.getResponse().getContentAsString(), Map.class);

        Number requestId1 = (Number) response1.get("requestId");
        Number requestId2 = (Number) response2.get("requestId");

        assertNotEquals(requestId1.longValue(), requestId2.longValue(),
                "Request IDs should be unique");
        assertTrue(requestId2.longValue() > requestId1.longValue(),
                "Request IDs should be incrementing");
    }
}
