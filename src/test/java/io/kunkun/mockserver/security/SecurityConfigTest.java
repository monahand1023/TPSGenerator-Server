package io.kunkun.mockserver.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SecurityConfig with a valid ADMIN_PASSWORD (provided via the "test" profile
 * application-test.properties so the context loads successfully).
 */
@SpringBootTest
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void passwordEncoderIsBCrypt() {
        assertInstanceOf(BCryptPasswordEncoder.class, passwordEncoder);
    }

    @Test
    void passwordEncoderCanEncodeAndMatch() {
        String raw = "mySecret";
        String encoded = passwordEncoder.encode(raw);
        assertTrue(passwordEncoder.matches(raw, encoded));
    }

    @Test
    void passwordEncoderDoesNotMatchWrongPassword() {
        String encoded = passwordEncoder.encode("correct");
        assertFalse(passwordEncoder.matches("wrong", encoded));
    }

    @Test
    void passwordEncoderProducesDifferentHashesEachTime() {
        String raw = "samePassword";
        String hash1 = passwordEncoder.encode(raw);
        String hash2 = passwordEncoder.encode(raw);
        // BCrypt uses a random salt per encode call
        assertNotEquals(hash1, hash2);
    }
}
