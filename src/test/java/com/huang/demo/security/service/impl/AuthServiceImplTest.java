package com.huang.demo.security.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.common.exception.BusinessException;
import com.huang.demo.common.exception.SecurityErrorCode;
import com.huang.demo.security.api.dto.AuthTokenResponse;
import com.huang.demo.security.api.dto.LoginRequest;
import com.huang.demo.security.config.ApiSecurityProperties;
import com.huang.demo.security.domain.SecurityUser;
import com.huang.demo.security.repository.SecurityUserMapper;
import com.huang.demo.security.service.JwtTokenService;
import com.huang.demo.security.service.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private ApiSecurityProperties properties;
    private SecurityUserMapper userMapper;
    private PasswordService passwordService;
    private JwtTokenService jwtTokenService;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        properties = new ApiSecurityProperties();
        properties.setInitEnabled(false);
        properties.setJwtSecret("unit-test-jwt-secret-please-change-in-prod");
        properties.setAccessTokenExpireMinutes(10);
        properties.setRefreshTokenExpireMinutes(30);
        userMapper = mock(SecurityUserMapper.class);
        passwordService = new PasswordService();
        jwtTokenService = new JwtTokenService(properties, new ObjectMapper());
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        authService = new AuthServiceImpl(properties, userMapper, passwordService, jwtTokenService, redisTemplate);
    }

    
    @org.junit.jupiter.api.Test
    void refreshFailsAfterLogoutRevocation() {
        // QA-07：登出撤销后旧 refresh 不可再用（Redis 黑名单命中）
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.startsWith("auth:refresh:revoked:")))
                .thenReturn(true);
        AuthServiceImpl revokedService = new AuthServiceImpl(properties, userMapper, passwordService, jwtTokenService, redisTemplate);
        com.huang.demo.common.exception.BusinessException ex = assertThrows(
                com.huang.demo.common.exception.BusinessException.class,
                () -> revokedService.refresh(validRefreshToken()));
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("已撤销");
    }

    @org.junit.jupiter.api.Test
    void logoutWritesRevocationWithTtl() {
        org.springframework.data.redis.core.StringRedisTemplate redisTemplate =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOps);
        AuthServiceImpl service = new AuthServiceImpl(properties, userMapper, passwordService, jwtTokenService, redisTemplate);
        service.logout(validRefreshToken());
        org.mockito.Mockito.verify(valueOps).set(
                org.mockito.ArgumentMatchers.startsWith("auth:refresh:revoked:"),
                org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    private String validRefreshToken() {
        return jwtTokenService.createRefreshToken("admin", "admin",
                new java.util.LinkedHashSet<>(java.util.Arrays.asList("ADMIN")));
    }

@Test
    void loginReturnsJwtTokensForEnabledUser() {
        when(userMapper.findByUsername("admin")).thenReturn(Optional.of(user("admin", "ADMIN,USER", "secret123")));
        LoginRequest request = new LoginRequest();
        request.setUsername(" admin ");
        request.setPassword("secret123");

        AuthTokenResponse response = authService.login(request);

        assertEquals("user-admin", response.getUserId());
        assertEquals("admin", response.getUsername());
        assertEquals("Bearer", response.getTokenType());
        assertTrue(response.getRoles().contains("ADMIN"));
        assertNotNull(response.getAccessToken());
        assertNotNull(response.getRefreshToken());
        assertTrue(jwtTokenService.resolveAccessToken("Bearer " + response.getAccessToken()).isPresent());
        assertTrue(jwtTokenService.parseRefreshToken(response.getRefreshToken()).isPresent());
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userMapper.findByUsername("admin")).thenReturn(Optional.of(user("admin", "ADMIN", "secret123")));
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("bad");

        BusinessException exception = assertThrows(BusinessException.class, () -> authService.login(request));

        assertEquals(SecurityErrorCode.UNAUTHORIZED, exception.getErrorCode());
        assertEquals("用户名或密码错误", exception.getMessage());
    }

    @Test
    void loginRejectsDisabledUser() {
        SecurityUser user = user("admin", "ADMIN", "secret123");
        user.setStatus("DISABLED");
        when(userMapper.findByUsername("admin")).thenReturn(Optional.of(user));
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("secret123");

        BusinessException exception = assertThrows(BusinessException.class, () -> authService.login(request));

        assertEquals(SecurityErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void refreshIssuesNewTokensForEnabledUser() {
        SecurityUser user = user("admin", "ADMIN,USER", "secret123");
        String refreshToken = jwtTokenService.createRefreshToken(user.getUserId(), user.getUsername(),
                responseRoles("ADMIN"));
        when(userMapper.findByUserId(user.getUserId())).thenReturn(Optional.of(user));

        AuthTokenResponse response = authService.refresh(refreshToken);

        assertEquals(user.getUserId(), response.getUserId());
        assertTrue(jwtTokenService.resolveAccessToken("Bearer " + response.getAccessToken()).isPresent());
    }

    @Test
    void initializeSkipsDatabaseWhenDisabled() {
        authService.initialize();

        verify(userMapper, never()).createTableIfAbsent();
        verify(userMapper, never()).insert(any(SecurityUser.class));
    }

    @Test
    void initializeCreatesBootstrapAdminWhenConfigured() {
        properties.setInitEnabled(true);
        properties.setBootstrapAdminEnabled(true);
        properties.setBootstrapAdminUserId("admin-1");
        properties.setBootstrapAdminUsername("root");
        properties.setBootstrapAdminPassword("secret123");
        when(userMapper.countByUsername("root")).thenReturn(0);

        authService.initialize();

        verify(userMapper).createTableIfAbsent();
        ArgumentCaptor<SecurityUser> captor = ArgumentCaptor.forClass(SecurityUser.class);
        verify(userMapper).insert(captor.capture());
        SecurityUser inserted = captor.getValue();
        assertEquals("admin-1", inserted.getUserId());
        assertEquals("root", inserted.getUsername());
        assertEquals("ADMIN,USER", inserted.getRoles());
        assertTrue(passwordService.matches("secret123", inserted.getPasswordHash()));
    }

    private SecurityUser user(String username, String roles, String rawPassword) {
        LocalDateTime now = LocalDateTime.now();
        return SecurityUser.builder()
                .id(1L)
                .userId("user-" + username)
                .username(username)
                .passwordHash(passwordService.hash(rawPassword))
                .roles(roles)
                .status("ENABLED")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private java.util.Set<String> responseRoles(String role) {
        java.util.Set<String> roles = new java.util.LinkedHashSet<String>();
        roles.add(role);
        return roles;
    }
}
