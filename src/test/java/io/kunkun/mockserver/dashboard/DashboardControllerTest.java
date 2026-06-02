package io.kunkun.mockserver.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the dashboard ingestion + read API and UI. No api-key is configured in the test
 * profile, so ingestion is open (development mode).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String json(Map<String, Object> m) throws Exception {
        return objectMapper.writeValueAsString(m);
    }

    @Test
    void fullLifecycle_registerUpdateFinish_isVisibleViaReadApi() throws Exception {
        String testId = "run-lifecycle-1";

        // register
        mockMvc.perform(post("/api/tests/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "testId", testId,
                                "testName", "My Load Test",
                                "targetServiceUrl", "http://localhost:8080",
                                "startTime", 1000L,
                                "testDuration", 60000L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("registered"));

        // appears in the list as running
        mockMvc.perform(get("/api/tests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tests[?(@.testId=='" + testId + "')].status", hasItem("running")));

        // metrics update
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalRequests", 500);
        summary.put("successRate", 0.99);
        summary.put("currentTps", 42);
        summary.put("p95ResponseTime", 120);
        mockMvc.perform(post("/api/metrics/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("testId", testId, "summary", summary,
                                "statusCodes", Map.of("200", 495, "500", 5)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("updated"));

        // detail reflects the update
        mockMvc.perform(get("/api/tests/" + testId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.currentTps").value(42))
                .andExpect(jsonPath("$.summary.successRate").value(0.99));

        // finish
        mockMvc.perform(post("/api/tests/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("testId", testId, "endTime", 61000L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("finished"));

        mockMvc.perform(get("/api/tests/" + testId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("finished"));
    }

    @Test
    void update_unknownTest_returns404() throws Exception {
        mockMvc.perform(post("/api/metrics/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("testId", "does-not-exist", "summary", Map.of()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void result_withoutPriorRegister_isAccepted() throws Exception {
        mockMvc.perform(post("/api/tests/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("testId", "result-only", "testName", "r", "successRate", 1.0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("result-recorded"));

        mockMvc.perform(get("/api/tests/result-only"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("finished"));
    }

    @Test
    void getUnknownTest_returns404() throws Exception {
        mockMvc.perform(get("/api/tests/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void dashboardPage_servesHtml() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("TPS Generator")));
    }
}
