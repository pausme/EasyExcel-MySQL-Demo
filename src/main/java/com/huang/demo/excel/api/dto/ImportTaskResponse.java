package com.huang.demo.excel.api.dto;

import com.huang.demo.excel.domain.model.StudentImportTaskResult;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ImportTaskResponse {

    private final String taskId;

    private final String ownerId;

    private final String status;

    private final Integer progressPercent;

    private final Long totalCount;

    private final Long completedCount;

    private final Integer retryCount;

    private final Integer maxRetryCount;

    private final String errorMessage;

    private final Integer errorCount;

    private final String errorFileName;

    private final Boolean hasErrorFile;

    private final LocalDateTime createdAt;

    private final LocalDateTime startedAt;

    private final LocalDateTime finishedAt;

    public static ImportTaskResponse from(AsyncTaskResponse task) {
        return from(task, null);
    }

    public static ImportTaskResponse from(AsyncTaskResponse task, StudentImportTaskResult result) {
        return ImportTaskResponse.builder()
                .taskId(task.getTaskId())
                .ownerId(task.getOwnerId())
                .status(task.getStatus().name())
                .progressPercent(task.getProgressPercent())
                .totalCount(task.getTotalCount())
                .completedCount(task.getCompletedCount())
                .retryCount(task.getRetryCount())
                .maxRetryCount(task.getMaxRetryCount())
                .errorMessage(task.getErrorMessage())
                .errorCount(result == null ? 0 : result.getErrorCount())
                .errorFileName(result == null ? null : result.getErrorFileName())
                .hasErrorFile(result != null
                        && result.getErrorObjectKey() != null
                        && !result.getErrorObjectKey().trim().isEmpty())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .build();
    }
}
