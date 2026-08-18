package com.huang.demo.excel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportTask {

    private String taskId;

    private String ownerId;

    private volatile ExportTaskStatus status;

    private volatile int progressPercent;

    private Long snapshotMaxId;

    private Long snapshotVersion;

    private volatile int total;

    private volatile int exported;

    private volatile int sheetCount;

    private volatile int retryCount;

    private volatile int maxRetryCount;

    private String fileName;

    private StudentExportFormat format;

    private StudentExportQuery query;

    private String objectKey;

    private volatile String errorMessage;

    private volatile String failureType;

    private volatile Boolean retryable;

    private volatile String failureSuggestion;

    private volatile Boolean canRetry;

    private volatile LocalDateTime createdAt;

    private volatile LocalDateTime finishedAt;
}
