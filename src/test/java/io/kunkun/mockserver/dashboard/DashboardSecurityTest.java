package io.kunkun.mockserver.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * When dashboard.api-key is configured, ingestion POSTs require a matching X-API-Key.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "dashboard.api-key=dash-secret")
class DashboardSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String registerBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of("testId", "sec-1", "testName", "t",
                "startTime", 1L, "testDuration", 1000L));
    }

    @Test
    void ingestion_withoutApiKey_returns401() throws Exception {
        mockMvc.perform(post("/api/tests/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ingestion_withWrongApiKey_returns401() throws Exception {
        mockMvc.perform(post("/api/tests/register")
                        .header("X-API-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ingestion_withCorrectApiKey_succeeds() throws Exception {
        mockMvc.perform(post("/api/tests/register")
                        .header("X-API-Key", "dash-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody()))
                .andExpect(status().isOk());
    }

    @Test
    void readApi_isOpenWithoutApiKey() throws Exception {
        mockMvc.perform(get("/api/tests"))
                .andExpect(status().isOk());
    }
}
