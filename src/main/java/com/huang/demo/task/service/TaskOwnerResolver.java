package com.huang.demo.task.service;

import com.huang.demo.task.config.TaskCenterProperties;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

@Component
public class TaskOwnerResolver {

    public static final String USER_ID_HEADER = "X-User-Id";

    private final TaskCenterProperties properties;

    public TaskOwnerResolver(TaskCenterProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        String ownerId = request == null ? null : request.getHeader(USER_ID_HEADER);
        if (ownerId == null || ownerId.trim().isEmpty()) {
            ownerId = properties.getDefaultOwnerId();
        }
        ownerId = ownerId == null ? "anonymous" : ownerId.trim();
        return ownerId.length() > 64 ? ownerId.substring(0, 64) : ownerId;
    }
}
