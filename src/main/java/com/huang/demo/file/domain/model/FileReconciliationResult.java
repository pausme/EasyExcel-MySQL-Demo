package com.huang.demo.file.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileReconciliationResult {

    private long fileRecordsChecked;

    private long missingFileRecords;

    private long uploadTasksChecked;

    private long expiredUploadTasks;

    private long orphanObjects;

    private long cleanupFailures;

    private long compensationRecords;

    public static FileReconciliationResult empty() {
        return FileReconciliationResult.builder().build();
    }
}
