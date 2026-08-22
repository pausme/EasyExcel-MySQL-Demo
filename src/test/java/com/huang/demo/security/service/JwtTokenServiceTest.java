package com.huang.demo.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.security.config.ApiSecurityProperties;
import com.huang.demo.security.domain.CurrentUser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenServiceTest {

    @Test
    void accessTokenCanBeResolvedToCurrentUser() {
        JwtTokenService service = createService();
        Set<String> roles = new LinkedHashSet<String>(Arrays.asList("ADMIN", "USER"));

        String token = service.createAccessToken("user-1", "admin", roles);
        Optional<CurrentUser> user = service.resolveAccessToken("Bearer " + token);

        assertTrue(user.isPresent());
        assertEquals("user-1", user.get().getUserId());
        assertTrue(user.get().hasRole("ADMIN"));
        assertFalse(user.get().isDemoUser());
    }

    @Test
    void refreshTokenIsNotAcceptedAsAccessToken() {
        JwtTokenService service = createService();
        String token = service.createRefreshToken("user-1", "admin",
                new LinkedHashSet<String>(Arrays.asList("ADMIN")));

        assertFalse(service.resolveAccessToken("Bearer " + token).isPresent());
        assertTrue(service.parseRefreshToken(token).isPresent());
    }

    @Test
    void tamperedTokenReturnsEmpty() {
        JwtTokenService service = createService();
        String token = service.createAccessToken("user-1", "admin",
                new LinkedHashSet<String>(Arrays.asList("USER")));

        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertFalse(service.resolveAccessToken("Bearer " + tampered).isPresent());
    }

    @Test
    void createTokenRequiresJwtSecret() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        JwtTokenService service = new JwtTokenService(properties, new ObjectMapper());

        assertThrows(IllegalStateException.class,
                () -> service.createAccessToken("user-1", "admin",
                        new LinkedHashSet<String>(Arrays.asList("USER"))));
    }

    @Test
    void createTokenRejectsShortJwtSecret() {
        // A16 修复：密钥长度不足 32 直接拒绝，而非以弱密钥签发
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setJwtSecret("short-secret");
        JwtTokenService service = new JwtTokenService(properties, new ObjectMapper());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.createAccessToken("user-1", "admin",
                        new LinkedHashSet<String>(Arrays.asList("USER"))));
        assertTrue(ex.getMessage().contains("长度不足"));
    }

    private JwtTokenService createService() {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setJwtSecret("unit-test-jwt-secret-please-change-in-prod");
        properties.setAccessTokenExpireMinutes(15);
        properties.setRefreshTokenExpireMinutes(60);
        return new JwtTokenService(properties, new ObjectMapper());
    }
}
