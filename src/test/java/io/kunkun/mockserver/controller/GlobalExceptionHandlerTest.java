package io.kunkun.mockserver.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// The /admin/** validation calls below now require auth; run as an authenticated admin so the
// requests reach the controller and exercise the exception handlers (400) rather than 401.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "testadmin", roles = "ADMIN")
class GlobalExceptionHandlerTest {

    private static final String ADMIN_USER = "testadmin";
    private static final String ADMIN_PASS = "testpassword";

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
                        .with(httpBasic(ADMIN_USER, ADMIN_PASS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    // ========== ConstraintViolationException Tests ==========

    @Test
    void handleConstraintViolation_returnsBadRequestWithMessage() {
        // Test the handler method directly (ConstraintViolationException is hard to trigger
        // through MockMvc without a @RequestParam @Min annotation on a controller method)
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("value must be positive");
        ConstraintViolationException ex = new ConstraintViolationException(
                "Constraint violated", Set.of(violation));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) handler.handleConstraintViolation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
        assertTrue(response.getBody().get("message").toString().contains("value must be positive"));
    }

    @Test
    void handleConstraintViolation_multipleViolations_joinsMessages() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ConstraintViolation<?> v1 = mock(ConstraintViolation.class);
        when(v1.getMessage()).thenReturn("must not be null");
        ConstraintViolation<?> v2 = mock(ConstraintViolation.class);
        when(v2.getMessage()).thenReturn("must be positive");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(v1, v2));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) handler.handleConstraintViolation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        String message = response.getBody().get("message").toString();
        // Both messages should appear (joined by ", ")
        assertTrue(message.contains("must not be null") || message.contains("must be positive"));
    }

    // ========== Generic Exception Tests ==========

    @Test
    void handleGenericException_returns500WithCorrelationId() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Exception ex = new RuntimeException("something went wrong");

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) handler.handleGenericException(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("error", response.getBody().get("status"));
        String message = response.getBody().get("message").toString();
        // After FIX 8 the message includes a correlation id ref
        assertTrue(message.contains("Internal server error"));
    }

    @Test
    void handleGenericException_eachCallProducesDifferentCorrelationId() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Exception ex = new RuntimeException("oops");

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> r1 =
                (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) handler.handleGenericException(ex);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> r2 =
                (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) handler.handleGenericException(ex);

        String msg1 = r1.getBody().get("message").toString();
        String msg2 = r2.getBody().get("message").toString();
        // Each call should produce a unique correlation id
        assertNotEquals(msg1, msg2);
    }
}
