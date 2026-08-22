package com.huang.demo.excel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.huang.demo.excel.model.StudentImportErrorRow;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentImportTaskResult {

    private int importedCount;

    private int validatedCount;

    private int batchCount;

    private String importMode;

    private int errorCount;

    private String errorFileName;

    private String errorObjectKey;

    private Map<String, Integer> errorSummary;

    private List<StudentImportErrorRow> errorPreviewRows;
}
