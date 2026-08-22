package com.huang.demo.common.resilience;

import com.huang.demo.task.service.TaskOwnerResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;
    private final TaskOwnerResolver taskOwnerResolver;

    public RateLimitInterceptor(RateLimitService rateLimitService,
                                TaskOwnerResolver taskOwnerResolver) {
        this.rateLimitService = rateLimitService;
        this.taskOwnerResolver = taskOwnerResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String scope = resolveScope(request);
        if (scope == null) {
            return true;
        }
        RateLimitResult result = rateLimitService.tryAcquire(scope, resolveIdentity(request));
        if (result.isAllowed()) {
            return true;
        }
        response.setHeader("Retry-After", String.valueOf(result.getRetryAfterSeconds()));
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
    }

    private String resolveIdentity(HttpServletRequest request) {
        String ownerId = taskOwnerResolver.resolve(request);
        if (ownerId != null && !ownerId.trim().isEmpty()) {
            return ownerId;
        }
        return request.getRemoteAddr();
    }

    private String resolveScope(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        if ("POST".equalsIgnoreCase(method) && "/api/excel/export".equals(uri)) {
            return "excel-export-submit";
        }
        if ("POST".equalsIgnoreCase(method) && "/api/excel/import".equals(uri)) {
            return "excel-import-submit";
        }
        if ("POST".equalsIgnoreCase(method) && "/api/files/upload".equals(uri)) {
            return "file-upload";
        }
        if ("POST".equalsIgnoreCase(method) && "/api/files/direct/init".equals(uri)) {
            return "file-direct-init";
        }
        if ("POST".equalsIgnoreCase(method) && "/api/files/multipart/init".equals(uri)) {
            return "file-multipart-init";
        }
        if ("GET".equalsIgnoreCase(method) && uri.matches("^/api/files/[^/]+/download$")) {
            return "file-download-sign";
        }
        if ("GET".equalsIgnoreCase(method) && uri.matches("^/api/excel/export/[^/]+/download$")) {
            return "excel-export-download-sign";
        }
        if ("GET".equalsIgnoreCase(method) && uri.matches("^/api/excel/import/[^/]+/error-file$")) {
            return "excel-import-error-download-sign";
        }
        return null;
    }
}
