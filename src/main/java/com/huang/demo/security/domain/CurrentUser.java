package com.huang.demo.security.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUser {

    private String userId;

    private Set<String> roles;

    private boolean demoUser;

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public static CurrentUser demo(String userId) {
        Set<String> roles = new HashSet<String>();
        roles.add("USER");
        return CurrentUser.builder()
                .userId(userId)
                .roles(Collections.unmodifiableSet(roles))
                .demoUser(true)
                .build();
    }

    public static CurrentUser authenticated(String userId, String role) {
        Set<String> roles = new HashSet<String>();
        roles.add(role);
        return CurrentUser.builder()
                .userId(userId)
                .roles(Collections.unmodifiableSet(roles))
                .demoUser(false)
                .build();
    }
}
