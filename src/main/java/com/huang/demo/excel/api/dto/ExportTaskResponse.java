package com.huang.demo.excel.api.dto;

import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.ExportTaskStatus;
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

    private ExportTaskStatus status;

    private int total;

    private int exported;

    private int sheetCount;

    private String fileName;

    private String storageType;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    public static ExportTaskResponse from(ExportTask task) {
        return ExportTaskResponse.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .total(task.getTotal())
                .exported(task.getExported())
                .sheetCount(task.getSheetCount())
                .fileName(task.getFileName())
                .storageType(task.getStorageType())
                .errorMessage(task.getErrorMessage())
                .createdAt(task.getCreatedAt())
                .finishedAt(task.getFinishedAt())
                .build();
    }
}
