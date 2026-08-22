package com.huang.demo.security.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityUser {

    private Long id;

    private String userId;

    private String username;

    private String passwordHash;

    private String roles;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
