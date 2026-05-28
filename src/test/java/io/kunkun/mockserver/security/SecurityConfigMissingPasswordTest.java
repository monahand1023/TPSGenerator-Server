package io.kunkun.mockserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextException;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that the application fails to start when ADMIN_PASSWORD is blank.
 *
 * We intentionally do NOT annotate this class with @SpringBootTest because that would
 * fail the whole test class at startup rather than letting us assert on the failure.
 * Instead, we launch a Spring context programmatically inside the test body.
 */
class SecurityConfigMissingPasswordTest {

    @Test
    void contextFailsToLoadWithBlankPassword() {
        SpringApplication app = new SpringApplication(
                io.kunkun.mockserver.MockHttpServerApplication.class);
        app.setDefaultProperties(java.util.Map.of(
                "ADMIN_PASSWORD", "",
                "spring.main.web-application-type", "none"
        ));
        assertThrows(Exception.class, app::run,
                "Expected context startup to throw when ADMIN_PASSWORD is blank");
    }
}
