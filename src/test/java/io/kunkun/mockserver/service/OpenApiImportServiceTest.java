package io.kunkun.mockserver.service;

import io.kunkun.mockserver.config.MockServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiImportServiceTest {

    private MockEndpointService endpointService;
    private OpenApiImportService importService;

    @BeforeEach
    void setUp() {
        endpointService = new MockEndpointService(new MockServerProperties());
        importService = new OpenApiImportService(endpointService);
    }

    @Test
    void importsEachPathAsEndpointWithSummary() {
        Map<String, Object> spec = Map.of("paths", Map.of(
                "/users", Map.of("get", Map.of("summary", "List users")),
                "/orders", Map.of("post", Map.of("summary", "Create order"))));

        List<String> imported = importService.importSpec(spec);

        assertEquals(2, imported.size());
        assertTrue(endpointService.getEndpointConfig("/users").isPresent());
        assertEquals("List users", endpointService.getEndpointConfig("/users").get().getResponseMessage());
        assertEquals("Create order", endpointService.getEndpointConfig("/orders").get().getResponseMessage());
    }

    @Test
    void importsResponseExampleAsBody() {
        Map<String, Object> spec = Map.of("paths", Map.of(
                "/widget", Map.of("get", Map.of(
                        "summary", "Get widget",
                        "responses", Map.of("200", Map.of(
                                "content", Map.of("application/json", Map.of(
                                        "example", Map.of("id", 1, "name", "w")))))))));

        importService.importSpec(spec);

        String body = endpointService.getEndpointConfig("/widget").orElseThrow().getResponseBody();
        assertNotNull(body);
        assertTrue(body.contains("\"id\""), body);
        assertTrue(body.contains("\"name\""), body);
    }

    @Test
    void missingPaths_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> importService.importSpec(Map.of("openapi", "3.0.0")));
    }

    @Test
    void defaultsSummaryWhenAbsent() {
        Map<String, Object> spec = Map.of("paths", Map.of("/bare", Map.of("get", Map.of())));
        importService.importSpec(spec);
        assertEquals("Mock for /bare",
                endpointService.getEndpointConfig("/bare").orElseThrow().getResponseMessage());
    }
}
