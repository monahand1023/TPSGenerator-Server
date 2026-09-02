package io.kunkun.mockserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that the application fails to start when ADMIN_PASSWORD is blank.
 *
 * We intentionally do NOT annotate this class with @SpringBootTest because that would
 * fail the whole test class at startup rather than letting us assert on the failure.
 * Instead, we launch a Spring context programmatically inside the test body.
 *
 * The blank password is passed as a command-line argument rather than via
 * setDefaultProperties: default properties are the lowest-precedence source, so an
 * ADMIN_PASSWORD environment variable (which CI sets for the auth tests) would silently
 * override the blank and the context would start normally. Command-line arguments outrank
 * environment variables, so the blank always wins here.
 */
class SecurityConfigMissingPasswordTest {

    @Test
    void contextFailsToLoadWithBlankPassword() {
        SpringApplication app = new SpringApplication(
                io.kunkun.mockserver.MockHttpServerApplication.class);
        app.setDefaultProperties(java.util.Map.of(
                "spring.main.web-application-type", "none"
        ));
        assertThrows(Exception.class, () -> app.run("--ADMIN_PASSWORD="),
                "Expected context startup to throw when ADMIN_PASSWORD is blank");
    }
}
