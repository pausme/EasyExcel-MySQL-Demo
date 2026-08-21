package com.huang.demo.task.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AsyncTaskEventResponse {

    private final String eventType;

    private final String message;

    private final LocalDateTime happenedAt;
}
