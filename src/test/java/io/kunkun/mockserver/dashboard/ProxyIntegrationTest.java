package io.kunkun.mockserver.dashboard;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end test of the record/replay proxy: an unconfigured path is forwarded to a real in-JVM
 * upstream, the upstream response is returned, and the endpoint is captured for replay.
 */
@SpringBootTest(properties = {"mock-server.proxy.enabled=true", "mock-server.proxy.record=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "testadmin", roles = "ADMIN")
class ProxyIntegrationTest {

    private static HttpServer upstream;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        try {
            upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        upstream.createContext("/", exchange -> {
            byte[] body = "{\"from\":\"upstream\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        upstream.start();
        registry.add("mock-server.proxy.upstream-url",
                () -> "http://localhost:" + upstream.getAddress().getPort());
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unconfiguredPath_isForwardedToUpstreamAndRecordedForReplay() throws Exception {
        // First request to an unconfigured path → forwarded to the upstream, upstream body returned.
        mockMvc.perform(get("/proxied"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("upstream")));

        // The proxy recorded it as a local endpoint (so it replays without hitting the upstream again).
        mockMvc.perform(get("/admin/config/proxied"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseBody", containsString("upstream")))
                .andExpect(jsonPath("$.statusDistribution.200").value(100));

        // Subsequent request is now served locally (replay) and still returns the captured body.
        mockMvc.perform(get("/proxied"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("upstream")));
    }
}
