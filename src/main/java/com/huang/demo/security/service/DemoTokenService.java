package com.huang.demo.security.service;

import com.huang.demo.security.config.ApiSecurityProperties;
import com.huang.demo.security.domain.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DemoTokenService {

    private final ApiSecurityProperties properties;

    public DemoTokenService(ApiSecurityProperties properties) {
        this.properties = properties;
    }

    public Optional<CurrentUser> resolve(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (token.equals(properties.getDemoAdminToken())) {
            return Optional.of(CurrentUser.authenticated("admin", "ADMIN"));
        }
        if (token.equals(properties.getDemoUserToken())) {
            return Optional.of(CurrentUser.authenticated("user-1", "USER"));
        }
        return Optional.empty();
    }
}
