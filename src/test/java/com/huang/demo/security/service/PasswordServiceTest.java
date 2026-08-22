package com.huang.demo.security.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordServiceTest {

    @Test
    void hashUsesSaltAndCanVerifyPassword() {
        PasswordService service = new PasswordService();

        String first = service.hash("secret123");
        String second = service.hash("secret123");

        assertNotEquals(first, second);
        assertTrue(service.matches("secret123", first));
        assertFalse(service.matches("wrong", first));
    }

    @Test
    void matchesReturnsFalseForMalformedHash() {
        PasswordService service = new PasswordService();

        assertFalse(service.matches("secret123", "pbkdf2_sha256$bad$not-base64$hash"));
        assertFalse(service.matches("secret123", "plain-text"));
    }
}
