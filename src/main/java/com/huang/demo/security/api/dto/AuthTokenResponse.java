package com.huang.demo.security.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.Set;

@Getter
@Builder
public class AuthTokenResponse {

    private final String userId;

    private final String username;

    private final Set<String> roles;

    private final String tokenType;

    private final String accessToken;

    private final Long accessTokenExpiresInSeconds;

    private final String refreshToken;

    private final Long refreshTokenExpiresInSeconds;
}
