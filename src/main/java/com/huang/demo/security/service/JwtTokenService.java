package com.huang.demo.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.security.config.ApiSecurityProperties;
import com.huang.demo.security.domain.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class JwtTokenService {

    public static final String ACCESS_TOKEN_TYPE = "access";
    public static final String REFRESH_TOKEN_TYPE = "refresh";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final ApiSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public JwtTokenService(ApiSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String createAccessToken(String userId, String username, Set<String> roles) {
        return createToken(userId, username, roles, ACCESS_TOKEN_TYPE,
                Math.max(1, properties.getAccessTokenExpireMinutes()) * 60L);
    }

    public String createRefreshToken(String userId, String username, Set<String> roles) {
        return createToken(userId, username, roles, REFRESH_TOKEN_TYPE,
                Math.max(1, properties.getRefreshTokenExpireMinutes()) * 60L);
    }

    public long accessTokenExpiresInSeconds() {
        return Math.max(1, properties.getAccessTokenExpireMinutes()) * 60L;
    }

    public long refreshTokenExpiresInSeconds() {
        return Math.max(1, properties.getRefreshTokenExpireMinutes()) * 60L;
    }

    public Optional<CurrentUser> resolveAccessToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return parse(authorizationHeader.substring("Bearer ".length()).trim(), ACCESS_TOKEN_TYPE)
                .map(claims -> CurrentUser.authenticated(claims.userId, claims.roles));
    }

    public Optional<TokenClaims> parseRefreshToken(String refreshToken) {
        return parse(refreshToken, REFRESH_TOKEN_TYPE);
    }

    public Optional<TokenClaims> parse(String token, String expectedType) {
        if (!StringUtils.hasText(token) || !StringUtils.hasText(properties.getJwtSecret())) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String signedContent = parts[0] + "." + parts[1];
        byte[] expectedSignature = sign(signedContent);
        byte[] actualSignature;
        try {
            actualSignature = BASE64_URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (!java.security.MessageDigest.isEqual(expectedSignature, actualSignature)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    BASE64_URL_DECODER.decode(parts[1]), new TypeReference<Map<String, Object>>() {
                    });
            String type = stringValue(payload.get("typ"));
            if (!expectedType.equals(type)) {
                return Optional.empty();
            }
            long expireAt = longValue(payload.get("exp"));
            if (expireAt <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            String userId = stringValue(payload.get("sub"));
            String username = stringValue(payload.get("username"));
            Set<String> roles = rolesValue(payload.get("roles"));
            if (!StringUtils.hasText(userId) || roles.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new TokenClaims(userId, username, roles, type, expireAt));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String createToken(String userId, String username, Set<String> roles, String type, long expiresInSeconds) {
        if (!StringUtils.hasText(properties.getJwtSecret())) {
            throw new IllegalStateException("缺少 JWT 密钥配置 API_SECURITY_JWT_SECRET");
        }
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<String, Object>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sub", userId);
        payload.put("username", username);
        payload.put("roles", roles);
        payload.put("typ", type);
        payload.put("iat", now);
        payload.put("exp", now + expiresInSeconds);
        String signedContent = encodeJson(header) + "." + encodeJson(payload);
        return signedContent + "." + BASE64_URL_ENCODER.encodeToString(sign(signedContent));
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT 编码失败", ex);
        }
    }

    private byte[] sign(String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT 签名失败", ex);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Set<String> rolesValue(Object value) {
        Set<String> roles = new LinkedHashSet<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    roles.add(String.valueOf(item));
                }
            }
        }
        return roles;
    }

    public static class TokenClaims {

        private final String userId;
        private final String username;
        private final Set<String> roles;
        private final String type;
        private final long expireAtEpochSeconds;

        TokenClaims(String userId, String username, Set<String> roles, String type, long expireAtEpochSeconds) {
            this.userId = userId;
            this.username = username;
            this.roles = roles;
            this.type = type;
            this.expireAtEpochSeconds = expireAtEpochSeconds;
        }

        public String getUserId() {
            return userId;
        }

        public String getUsername() {
            return username;
        }

        public Set<String> getRoles() {
            return roles;
        }

        public String getType() {
            return type;
        }

        public long getExpireAtEpochSeconds() {
            return expireAtEpochSeconds;
        }
    }
}
