package com.huang.demo.task.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsyncTaskEventLog {

    private Long id;

    private String eventId;

    private String taskId;

    private String ownerId;

    private String taskType;

    private String eventType;

    private String message;

    private Integer progressPercent;

    private Long completedCount;

    private Long totalCount;

    private String failureType;

    private String traceId;

    private String workerId;

    private LocalDateTime createdAt;
}
