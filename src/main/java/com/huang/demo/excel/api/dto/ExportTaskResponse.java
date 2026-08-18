package com.huang.demo.excel.api.dto;

import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
import com.huang.demo.excel.domain.model.StudentExportFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportTaskResponse {

    private String taskId;

    private String ownerId;

    private ExportTaskStatus status;

    private int progressPercent;

    private int total;

    private int exported;

    private int sheetCount;

    private int retryCount;

    private int maxRetryCount;

    private String fileName;

    private StudentExportFormat format;

    private String errorMessage;

    private String failureType;

    private Boolean retryable;

    private String failureSuggestion;

    private Boolean canRetry;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    public static ExportTaskResponse from(ExportTask task) {
        return ExportTaskResponse.builder()
                .taskId(task.getTaskId())
                .ownerId(task.getOwnerId())
                .status(task.getStatus())
                .progressPercent(task.getProgressPercent())
                .total(task.getTotal())
                .exported(task.getExported())
                .sheetCount(task.getSheetCount())
                .retryCount(task.getRetryCount())
                .maxRetryCount(task.getMaxRetryCount())
                .fileName(task.getFileName())
                .format(task.getFormat())
                .errorMessage(task.getErrorMessage())
                .failureType(task.getFailureType())
                .retryable(task.getRetryable())
                .failureSuggestion(task.getFailureSuggestion())
                .canRetry(task.getCanRetry())
                .createdAt(task.getCreatedAt())
                .finishedAt(task.getFinishedAt())
                .build();
    }
}
