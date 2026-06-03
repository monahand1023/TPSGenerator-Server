package io.kunkun.mockserver.service;

import io.kunkun.mockserver.config.MockServerProperties;
import io.kunkun.mockserver.dto.MockEndpointConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProxyServiceTest {

    @Test
    void buildRecordedConfig_capturesBodyStatusContentTypeAndLatency() {
        MockEndpointConfig c = ProxyService.buildRecordedConfig(201, "application/json", "{\"a\":1}", 42);

        assertEquals(42, c.getMinDelay());
        assertEquals(42, c.getMaxDelay());
        assertEquals("{\"a\":1}", c.getResponseBody());
        assertEquals(Map.of("201", 100), c.getStatusDistribution());
        assertEquals("application/json", c.getResponseHeaders().get("Content-Type"));
    }

    @Test
    void buildRecordedConfig_handlesNullBodyAndContentType() {
        MockEndpointConfig c = ProxyService.buildRecordedConfig(204, null, null, 0);

        assertEquals("", c.getResponseBody());
        assertFalse(c.getResponseHeaders().containsKey("Content-Type"));
        assertEquals(Map.of("204", 100), c.getStatusDistribution());
    }

    @Test
    void isEnabled_requiresEnabledFlagAndUpstreamUrl() {
        MockServerProperties props = new MockServerProperties();
        ProxyService svc = new ProxyService(props,
                new MockEndpointService(props),
                new StatisticsService(new SimpleMeterRegistry()));

        assertFalse(svc.isEnabled(), "disabled by default");

        props.getProxy().setEnabled(true);
        assertFalse(svc.isEnabled(), "enabled but no upstream URL");

        props.getProxy().setUpstreamUrl("http://upstream:9000");
        assertTrue(svc.isEnabled());
    }
}
