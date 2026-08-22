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

    public AuthServiceImpl(ApiSecurityProperties properties,
                           SecurityUserMapper userMapper,
                           PasswordService passwordService,
                           JwtTokenService jwtTokenService) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.jwtTokenService = jwtTokenService;
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
        SecurityUser user = userMapper.findByUserId(claims.getUserId())
                .orElseThrow(() -> new BusinessException(SecurityErrorCode.UNAUTHORIZED, "用户不存在或已停用"));
        assertEnabled(user);
        return buildTokenResponse(user);
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
