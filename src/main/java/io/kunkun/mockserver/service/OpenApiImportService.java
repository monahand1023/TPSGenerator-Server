package io.kunkun.mockserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates mock endpoints from an OpenAPI / Swagger spec (parsed as a JSON map). For each entry in
 * the spec's {@code paths} object an endpoint is registered using the current defaults, with a
 * response message derived from the operation summary and, when present, a response body taken from
 * a declared example.
 *
 * <p>Limitation: the mock matches paths exactly, so templated paths (e.g. {@code /users/{id}}) are
 * registered literally and won't match concrete request paths — static paths import cleanly.
 */
@Service
public class OpenApiImportService {

    private static final Logger logger = LoggerFactory.getLogger(OpenApiImportService.class);
    private static final List<String> HTTP_METHODS =
            List.of("get", "post", "put", "delete", "patch", "head", "options");

    private final MockEndpointService endpointService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenApiImportService(MockEndpointService endpointService) {
        this.endpointService = endpointService;
    }

    /**
     * Imports endpoints from the spec.
     *
     * @param spec the OpenAPI document parsed as a map
     * @return the normalized paths that were imported
     * @throws IllegalArgumentException if the spec has no usable {@code paths} object
     */
    @SuppressWarnings("unchecked")
    public List<String> importSpec(Map<String, Object> spec) {
        Object pathsObj = spec == null ? null : spec.get("paths");
        if (!(pathsObj instanceof Map)) {
            throw new IllegalArgumentException("OpenAPI spec has no 'paths' object");
        }
        Map<String, Object> paths = (Map<String, Object>) pathsObj;

        List<String> imported = new ArrayList<>();
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            String path = entry.getKey();
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> operations = (Map<String, Object>) entry.getValue();

            String summary = extractSummary(operations, path);
            String example = extractJsonExample(operations);

            MockEndpointConfig config = new MockEndpointConfig(
                    endpointService.getCurrentMinDelay(),
                    endpointService.getCurrentMaxDelay(),
                    0.0,
                    new HashMap<>(),
                    summary);
            if (example != null) {
                config.setResponseBody(example);
            }
            endpointService.configureEndpoint(path, config);
            imported.add(MockEndpointService.normalizePath(path));
        }
        logger.info("Imported {} endpoints from OpenAPI spec", imported.size());
        return imported;
    }

    @SuppressWarnings("unchecked")
    private String extractSummary(Map<String, Object> operations, String path) {
        for (String method : HTTP_METHODS) {
            Object op = operations.get(method);
            if (op instanceof Map) {
                Object summary = ((Map<String, Object>) op).get("summary");
                if (summary instanceof String && !((String) summary).isBlank()) {
                    return (String) summary;
                }
            }
        }
        return "Mock for " + path;
    }

    /** Best-effort extraction of a JSON response example from the first operation that declares one. */
    @SuppressWarnings("unchecked")
    private String extractJsonExample(Map<String, Object> operations) {
        for (String method : HTTP_METHODS) {
            Object opObj = operations.get(method);
            if (!(opObj instanceof Map)) {
                continue;
            }
            Object responsesObj = ((Map<String, Object>) opObj).get("responses");
            if (!(responsesObj instanceof Map)) {
                continue;
            }
            Map<String, Object> responses = (Map<String, Object>) responsesObj;
            Object response = responses.getOrDefault("200", responses.get("default"));
            if (!(response instanceof Map)) {
                continue;
            }
            Object contentObj = ((Map<String, Object>) response).get("content");
            if (!(contentObj instanceof Map)) {
                continue;
            }
            Object jsonObj = ((Map<String, Object>) contentObj).get("application/json");
            if (!(jsonObj instanceof Map)) {
                continue;
            }
            Object example = ((Map<String, Object>) jsonObj).get("example");
            if (example != null) {
                try {
                    return objectMapper.writeValueAsString(example);
                } catch (JsonProcessingException e) {
                    return example.toString();
                }
            }
        }
        return null;
    }
}
