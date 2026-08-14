package com.huang.demo.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.common.api.dto.ApiResponse;
import com.huang.demo.common.exception.CommonErrorCode;
import com.huang.demo.security.config.ApiSecurityProperties;
import com.huang.demo.security.domain.CurrentUser;
import com.huang.demo.security.domain.UserContextHolder;
import com.huang.demo.security.service.DemoTokenService;
import com.huang.demo.task.service.TaskOwnerResolver;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class UserContextInterceptor implements HandlerInterceptor {

    private final ApiSecurityProperties properties;
    private final DemoTokenService demoTokenService;
    private final ObjectMapper objectMapper;

    public UserContextInterceptor(ApiSecurityProperties properties,
                                  DemoTokenService demoTokenService,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.demoTokenService = demoTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Optional<CurrentUser> tokenUser = demoTokenService.resolve(request.getHeader("Authorization"));
        if (tokenUser.isPresent()) {
            UserContextHolder.set(tokenUser.get());
            return true;
        }
        if (properties.isDemoMode()) {
            UserContextHolder.set(CurrentUser.demo(resolveDemoUserId(request)));
            return true;
        }
        writeUnauthorized(response);
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        UserContextHolder.clear();
    }

    private String resolveDemoUserId(HttpServletRequest request) {
        String ownerId = request.getHeader(TaskOwnerResolver.USER_ID_HEADER);
        if (ownerId == null || ownerId.trim().isEmpty()) {
            ownerId = properties.getDemoDefaultUserId();
        }
        ownerId = ownerId == null ? "anonymous" : ownerId.trim();
        return ownerId.length() > 64 ? ownerId.substring(0, 64) : ownerId;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.failed(CommonErrorCode.UNAUTHORIZED.getCode(), CommonErrorCode.UNAUTHORIZED.getMessage())));
    }
}
