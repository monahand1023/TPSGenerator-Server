package io.kunkun.mockserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        // Reset stats before each test
        mockMvc.perform(post("/admin/stats/reset"))
                .andExpect(status().isOk());
    }

    // ========== Endpoint Configuration Tests ==========

    @Test
    void configureEndpoint_withValidConfig_returnsSuccess() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(50, 100, 0.1, new HashMap<>(), "Test response");

        mockMvc.perform(post("/admin/config/test-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Endpoint configured: /test-endpoint"))
                .andExpect(jsonPath("$.config.minDelay").value(50))
                .andExpect(jsonPath("$.config.maxDelay").value(100));
    }

    @Test
    void configureEndpoint_withNegativeMinDelay_returnsBadRequest() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(-10, 100, 0.1, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/test-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void configureEndpoint_withMinDelayGreaterThanMaxDelay_returnsBadRequest() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(200, 100, 0.1, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/test-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message", containsString("minDelay cannot exceed maxDelay")));
    }

    @Test
    void configureEndpoint_withInvalidErrorRate_returnsBadRequest() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(10, 100, 1.5, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/test-endpoint")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void getEndpointConfig_whenExists_returnsConfig() throws Exception {
        // First configure an endpoint
        MockEndpointConfig config = new MockEndpointConfig(25, 75, 0.05, new HashMap<>(), "Custom response");

        mockMvc.perform(post("/admin/config/my-api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        // Then retrieve it
        mockMvc.perform(get("/admin/config/my-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minDelay").value(25))
                .andExpect(jsonPath("$.maxDelay").value(75))
                .andExpect(jsonPath("$.errorRate").value(0.05))
                .andExpect(jsonPath("$.responseMessage").value("Custom response"));
    }

    @Test
    void getEndpointConfig_whenNotExists_returnsNotFound() throws Exception {
        mockMvc.perform(get("/admin/config/nonexistent-endpoint"))
                .andExpect(status().isNotFound());
    }

    // ========== Default Configuration Tests ==========

    @Test
    void configureDefaults_withValidValues_returnsSuccess() throws Exception {
        mockMvc.perform(post("/admin/defaults")
                        .param("minDelay", "20")
                        .param("maxDelay", "200")
                        .param("errorRate", "0.05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.defaultMinDelay").value(20))
                .andExpect(jsonPath("$.defaultMaxDelay").value(200))
                .andExpect(jsonPath("$.defaultErrorRate").value(0.05));
    }

    @Test
    void configureDefaults_withPartialValues_updatesOnlyProvided() throws Exception {
        // First set known values
        mockMvc.perform(post("/admin/defaults")
                        .param("minDelay", "10")
                        .param("maxDelay", "100")
                        .param("errorRate", "0.0"))
                .andExpect(status().isOk());

        // Update only minDelay
        mockMvc.perform(post("/admin/defaults")
                        .param("minDelay", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMinDelay").value(30))
                .andExpect(jsonPath("$.defaultMaxDelay").value(100))
                .andExpect(jsonPath("$.defaultErrorRate").value(0.0));
    }

    @Test
    void configureDefaults_withNegativeMinDelay_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/admin/defaults")
                        .param("minDelay", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message", containsString("minDelay must be non-negative")));
    }

    @Test
    void configureDefaults_withMinDelayGreaterThanMaxDelay_returnsBadRequest() throws Exception {
        // First set maxDelay to 50
        mockMvc.perform(post("/admin/defaults")
                        .param("maxDelay", "50"))
                .andExpect(status().isOk());

        // Try to set minDelay to 100 (greater than maxDelay)
        mockMvc.perform(post("/admin/defaults")
                        .param("minDelay", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("minDelay cannot exceed maxDelay")));
    }

    @Test
    void configureDefaults_withInvalidErrorRate_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/admin/defaults")
                        .param("errorRate", "2.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("errorRate must be between 0.0 and 1.0")));
    }

    // ========== Statistics Tests ==========

    @Test
    void getStats_returnsStatistics() throws Exception {
        mockMvc.perform(get("/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.totalRequests").isNumber())
                .andExpect(jsonPath("$.successfulRequests").isNumber())
                .andExpect(jsonPath("$.failedRequests").isNumber())
                .andExpect(jsonPath("$.successRate").isNumber());
    }

    @Test
    void resetStats_clearsStatistics() throws Exception {
        // Make a request to generate some stats
        mockMvc.perform(get("/test-path"))
                .andExpect(status().isOk());

        // Verify stats are non-zero
        mockMvc.perform(get("/admin/stats"))
                .andExpect(jsonPath("$.totalRequests", greaterThan(0)));

        // Reset stats
        mockMvc.perform(post("/admin/stats/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Statistics reset"));

        // Verify stats are reset
        mockMvc.perform(get("/admin/stats"))
                .andExpect(jsonPath("$.totalRequests").value(0))
                .andExpect(jsonPath("$.successfulRequests").value(0))
                .andExpect(jsonPath("$.failedRequests").value(0));
    }

    // ========== Delete Endpoint Tests ==========

    @Test
    void deleteEndpointConfig_whenExists_deletesAndReturnsSuccess() throws Exception {
        // First configure an endpoint
        MockEndpointConfig config = new MockEndpointConfig(10, 100, 0.0, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/delete-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        // Verify it exists
        mockMvc.perform(get("/admin/config/delete-test"))
                .andExpect(status().isOk());

        // Delete it
        mockMvc.perform(delete("/admin/config/delete-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value(containsString("deleted")));

        // Verify it's gone
        mockMvc.perform(get("/admin/config/delete-test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteEndpointConfig_whenNotExists_returnsNotFound() throws Exception {
        mockMvc.perform(delete("/admin/config/nonexistent-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void clearAllEndpointConfigs_deletesAllConfigs() throws Exception {
        // Configure multiple endpoints
        MockEndpointConfig config = new MockEndpointConfig(10, 100, 0.0, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/clear-test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/config/clear-test-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        // Clear all
        mockMvc.perform(delete("/admin/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.deletedCount").isNumber());

        // Verify they're gone
        mockMvc.perform(get("/admin/config/clear-test-1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/config/clear-test-2"))
                .andExpect(status().isNotFound());
    }

    // ========== Get All Configs Tests ==========

    @Test
    void getAllEndpointConfigs_returnsAllConfigs() throws Exception {
        // Clear first
        mockMvc.perform(delete("/admin/config"));

        // Configure some endpoints
        MockEndpointConfig config = new MockEndpointConfig(10, 100, 0.0, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/list-test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/config/list-test-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk());

        // Get all
        mockMvc.perform(get("/admin/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.endpoints").isMap());
    }

    // ========== Get Defaults Tests ==========

    @Test
    void getDefaults_returnsCurrentDefaults() throws Exception {
        // Set known defaults first
        mockMvc.perform(post("/admin/defaults")
                        .param("minDelay", "15")
                        .param("maxDelay", "150")
                        .param("errorRate", "0.1"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.defaultMinDelay").value(15))
                .andExpect(jsonPath("$.defaultMaxDelay").value(150))
                .andExpect(jsonPath("$.defaultErrorRate").value(0.1));
    }

    // ========== Persistence Status Tests ==========

    @Test
    void getPersistenceStatus_returnsStatus() throws Exception {
        mockMvc.perform(get("/admin/persistence/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.enabled").isBoolean())
                .andExpect(jsonPath("$.filePath").isString());
    }

    // ========== API Versioning Tests ==========

    @Test
    void versionedEndpoint_configureEndpoint_works() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(10, 100, 0.0, new HashMap<>(), "Versioned");

        mockMvc.perform(post("/api/v1/admin/config/versioned-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        // Verify via non-versioned endpoint
        mockMvc.perform(get("/admin/config/versioned-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseMessage").value("Versioned"));
    }

    @Test
    void versionedEndpoint_getStats_works() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));
    }
}
