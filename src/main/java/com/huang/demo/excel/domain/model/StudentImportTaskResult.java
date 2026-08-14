package com.huang.demo.excel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentImportTaskResult {

    private int importedCount;

    private int batchCount;

    private int errorCount;

    private String errorFileName;

    private String errorObjectKey;
}
