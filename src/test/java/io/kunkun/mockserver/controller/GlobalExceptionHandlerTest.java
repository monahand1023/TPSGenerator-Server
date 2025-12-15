package io.kunkun.mockserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ========== IllegalArgumentException Tests ==========

    @Test
    void handleIllegalArgument_returnsBadRequest() throws Exception {
        // Configure with invalid minDelay > maxDelay to trigger IllegalArgumentException
        MockEndpointConfig config = new MockEndpointConfig(200, 100, 0.1, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/test-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(containsString("minDelay cannot exceed maxDelay")));
    }

    @Test
    void handleIllegalArgument_forInvalidErrorRate_returnsBadRequest() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(10, 100, 1.5, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/test-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(containsString("errorRate")));
    }

    @Test
    void handleIllegalArgument_forNegativeDelay_returnsBadRequest() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(-10, 100, 0.1, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/test-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value(containsString("minDelay")));
    }

    // ========== Validation Exception Tests ==========

    @Test
    void handleValidationException_forInvalidDefaults_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/admin/defaults")
                        .param("minDelay", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void handleValidationException_forInvalidErrorRateInDefaults_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/admin/defaults")
                        .param("errorRate", "2.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    // ========== Response Format Tests ==========

    @Test
    void errorResponse_hasConsistentFormat() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(200, 100, 0.1, new HashMap<>(), "Test");

        mockMvc.perform(post("/admin/config/test-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    // ========== API Version Tests ==========

    @Test
    void handleIllegalArgument_worksOnVersionedEndpoint() throws Exception {
        MockEndpointConfig config = new MockEndpointConfig(200, 100, 0.1, new HashMap<>(), "Test");

        mockMvc.perform(post("/api/v1/admin/config/test-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }
}
