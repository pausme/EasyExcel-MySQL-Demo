package com.huang.demo.security.service;

import com.huang.demo.common.exception.BusinessException;
import com.huang.demo.common.exception.SecurityErrorCode;
import com.huang.demo.security.domain.SecurityRoles;
import com.huang.demo.security.domain.UserContextHolder;
import org.springframework.stereotype.Service;

@Service
public class PermissionService {

    public boolean isAdmin() {
        return UserContextHolder.get()
                .map(user -> user.hasRole(SecurityRoles.ADMIN))
                .orElse(false);
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw new BusinessException(SecurityErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }
}
