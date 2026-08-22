package com.huang.demo.excel.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.huang.demo.excel.domain.model.StudentImportTaskResult;
import com.huang.demo.excel.model.StudentImportErrorRow;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Getter
@Builder
@JsonDeserialize(builder = ImportTaskResponse.ImportTaskResponseBuilder.class)
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

    private final String failureType;

    private final Boolean retryable;

    private final String failureSuggestion;

    private final Boolean canRetry;

    private final Integer importedCount;

    private final Integer validatedCount;

    private final String importMode;

    private final Integer errorCount;

    private final String errorFileName;

    private final Boolean hasErrorFile;

    private final Map<String, Integer> errorSummary;

    private final List<StudentImportErrorRow> errorPreviewRows;

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
                .failureType(task.getFailureType() == null ? null : task.getFailureType().name())
                .retryable(task.getRetryable())
                .failureSuggestion(task.getFailureSuggestion())
                .canRetry(task.getCanRetry())
                .importedCount(result == null ? 0 : result.getImportedCount())
                .validatedCount(result == null ? 0 : result.getValidatedCount())
                .importMode(result == null ? null : result.getImportMode())
                .errorCount(result == null ? 0 : result.getErrorCount())
                .errorFileName(result == null ? null : result.getErrorFileName())
                .hasErrorFile(result != null
                        && result.getErrorObjectKey() != null
                        && !result.getErrorObjectKey().trim().isEmpty())
                .errorSummary(result == null || result.getErrorSummary() == null
                        ? Collections.<String, Integer>emptyMap()
                        : result.getErrorSummary())
                .errorPreviewRows(result == null || result.getErrorPreviewRows() == null
                        ? Collections.<StudentImportErrorRow>emptyList()
                        : result.getErrorPreviewRows())
                .createdAt(task.getCreatedAt())
                .startedAt(task.getStartedAt())
                .finishedAt(task.getFinishedAt())
                .build();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class ImportTaskResponseBuilder {
    }
}
