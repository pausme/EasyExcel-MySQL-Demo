package com.huang.demo.security.service;

import com.huang.demo.security.config.ApiSecurityProperties;
import com.huang.demo.security.domain.CurrentUser;
import com.huang.demo.security.domain.SecurityRoles;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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
        if (StringUtils.hasText(properties.getDemoAdminToken()) && token.equals(properties.getDemoAdminToken())) {
            return Optional.of(CurrentUser.authenticated("admin", SecurityRoles.ADMIN));
        }
        if (StringUtils.hasText(properties.getDemoUserToken()) && token.equals(properties.getDemoUserToken())) {
            return Optional.of(CurrentUser.authenticated("user-1", SecurityRoles.USER));
        }
        return Optional.empty();
    }
}
