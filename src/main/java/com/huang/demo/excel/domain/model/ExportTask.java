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

    private volatile int total;

    private volatile int exported;

    private volatile int sheetCount;

    private volatile int retryCount;

    private volatile int maxRetryCount;

    private String fileName;

    private String objectKey;

    private volatile String errorMessage;

    private volatile LocalDateTime createdAt;

    private volatile LocalDateTime finishedAt;
}
