package com.huang.demo.security.service.impl;

import com.huang.demo.common.exception.BusinessException;
import com.huang.demo.common.exception.SecurityErrorCode;
import com.huang.demo.security.api.dto.AuthTokenResponse;
import com.huang.demo.security.api.dto.LoginRequest;
import com.huang.demo.security.config.ApiSecurityProperties;
import com.huang.demo.security.domain.SecurityRoles;
import com.huang.demo.security.domain.SecurityUser;
import com.huang.demo.security.repository.SecurityUserMapper;
import com.huang.demo.security.service.AuthService;
import com.huang.demo.security.service.JwtTokenService;
import com.huang.demo.security.service.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final String STATUS_ENABLED = "ENABLED";

    private final ApiSecurityProperties properties;
    private final SecurityUserMapper userMapper;
    private final PasswordService passwordService;
    private final JwtTokenService jwtTokenService;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    public AuthServiceImpl(ApiSecurityProperties properties,
                           SecurityUserMapper userMapper,
                           PasswordService passwordService,
                           JwtTokenService jwtTokenService,
                           org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.jwtTokenService = jwtTokenService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PostConstruct
    public void initialize() {
        if (!properties.isInitEnabled()) {
            log.info("security user initialization skipped");
            return;
        }
        userMapper.createTableIfAbsent();
        bootstrapAdminIfNecessary();
        log.info("security user initialized");
    }

    @Override
    public AuthTokenResponse login(LoginRequest request) {
        if (request == null) {
            throw new BusinessException(SecurityErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        String username = normalizeRequired(request.getUsername(), 64, "用户名或密码错误");
        String password = normalizeRequired(request.getPassword(), 128, "用户名或密码错误");
        SecurityUser user = userMapper.findByUsername(username)
                .orElseThrow(() -> new BusinessException(SecurityErrorCode.UNAUTHORIZED, "用户名或密码错误"));
        assertEnabled(user);
        if (!passwordService.matches(password, user.getPasswordHash())) {
            throw new BusinessException(SecurityErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return buildTokenResponse(user);
    }

    @Override
    public AuthTokenResponse refresh(String refreshToken) {
        JwtTokenService.TokenClaims claims = jwtTokenService.parseRefreshToken(refreshToken)
                .orElseThrow(() -> new BusinessException(SecurityErrorCode.UNAUTHORIZED, "刷新令牌无效或已过期"));
        assertRefreshTokenNotRevoked(refreshToken);
        SecurityUser user = userMapper.findByUserId(claims.getUserId())
                .orElseThrow(() -> new BusinessException(SecurityErrorCode.UNAUTHORIZED, "用户不存在或已停用"));
        assertEnabled(user);
        return buildTokenResponse(user);
    }

    @Override
    public void logout(String refreshToken) {
        // 无状态 refresh token 撤销（QA-07）：登出时将 token 哈希写入 Redis 黑名单，TTL 取剩余有效期
        JwtTokenService.TokenClaims claims = jwtTokenService.parseRefreshToken(refreshToken).orElse(null);
        long remainingSeconds = claims == null
                ? jwtTokenService.refreshTokenExpiresInSeconds()
                : Math.max(60L, claims.getExpireAtEpochSeconds() - java.time.Instant.now().getEpochSecond());
        try {
            stringRedisTemplate.opsForValue().set(
                    buildRefreshRevocationKey(refreshToken), "1", java.time.Duration.ofSeconds(remainingSeconds));
        } catch (RuntimeException ex) {
            // Redis 不可用时降级：撤销信息丢失但登出仍返回成功（与任务缓存降级策略一致）
            log.warn("save refresh token revocation failed, degraded mode", ex);
        }
    }

    private void assertRefreshTokenNotRevoked(String refreshToken) {
        try {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(buildRefreshRevocationKey(refreshToken)))) {
                throw new BusinessException(SecurityErrorCode.UNAUTHORIZED, "刷新令牌已撤销，请重新登录");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Redis 不可用时放行（降级不阻断登录链路）
            log.warn("check refresh token revocation failed, degraded mode", ex);
        }
    }

    private String buildRefreshRevocationKey(String refreshToken) {
        return "auth:refresh:revoked:" + sha256Hex(refreshToken);
    }

    private String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private AuthTokenResponse buildTokenResponse(SecurityUser user) {
        Set<String> roles = parseRoles(user.getRoles());
        return AuthTokenResponse.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .roles(roles)
                .tokenType("Bearer")
                .accessToken(jwtTokenService.createAccessToken(user.getUserId(), user.getUsername(), roles))
                .accessTokenExpiresInSeconds(jwtTokenService.accessTokenExpiresInSeconds())
                .refreshToken(jwtTokenService.createRefreshToken(user.getUserId(), user.getUsername(), roles))
                .refreshTokenExpiresInSeconds(jwtTokenService.refreshTokenExpiresInSeconds())
                .build();
    }

    private void assertEnabled(SecurityUser user) {
        if (user == null || !STATUS_ENABLED.equals(user.getStatus())) {
            throw new BusinessException(SecurityErrorCode.FORBIDDEN, "用户不存在或已停用");
        }
    }

    private void bootstrapAdminIfNecessary() {
        if (!properties.isBootstrapAdminEnabled()) {
            return;
        }
        String username = normalizeOptional(properties.getBootstrapAdminUsername(), 64);
        String password = normalizeOptional(properties.getBootstrapAdminPassword(), 128);
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.warn("bootstrap admin skipped, username or password is empty");
            return;
        }
        if (userMapper.countByUsername(username) > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        userMapper.insert(SecurityUser.builder()
                .userId(normalizeUserId(properties.getBootstrapAdminUserId()))
                .username(username)
                .passwordHash(passwordService.hash(password))
                .roles(SecurityRoles.ADMIN + "," + SecurityRoles.USER)
                .status(STATUS_ENABLED)
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.info("bootstrap admin created, username={}", username);
    }

    private Set<String> parseRoles(String rolesText) {
        Set<String> roles = new LinkedHashSet<String>();
        if (rolesText != null) {
            String[] parts = rolesText.split(",");
            for (String part : parts) {
                String role = normalizeOptional(part, 32);
                if (StringUtils.hasText(role)) {
                    roles.add(role.toUpperCase());
                }
            }
        }
        if (roles.isEmpty()) {
            roles.add(SecurityRoles.USER);
        }
        return roles;
    }

    private String normalizeUserId(String value) {
        String normalized = normalizeOptional(value, 64);
        return StringUtils.hasText(normalized) ? normalized : "admin";
    }

    private String normalizeRequired(String value, int maxLength, String message) {
        String normalized = normalizeOptional(value, maxLength);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(SecurityErrorCode.UNAUTHORIZED, message);
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
}
