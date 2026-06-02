package io.kunkun.mockserver.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies that {@code mock-server.history.enabled=true} actually binds (nested History property)
 * and that requests are recorded under the bounded endpoint label.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "mock-server.history.enabled=true",
        "mock-server.default-min-delay=0",
        "mock-server.default-max-delay=1"})
@WithMockUser(username = "testadmin", roles = "ADMIN")
class RequestHistoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void historyEnabled_recordsRequestsUnderBoundedLabel() throws Exception {
        // Clear any prior history, then hit an unconfigured path
        mockMvc.perform(delete("/admin/history")).andExpect(status().isOk());
        mockMvc.perform(get("/history-probe")).andExpect(status().isOk());

        // Unconfigured paths collapse to the single "(unmatched)" bucket
        mockMvc.perform(get("/admin/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history").isMap())
                .andExpect(jsonPath("$.endpointCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.history['(unmatched)']").exists());
    }
}
