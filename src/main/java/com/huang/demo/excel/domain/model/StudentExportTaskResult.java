package com.huang.demo.excel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentExportTaskResult {

    private String fileName;

    private String objectKey;

    private StudentExportFormat format;

    private int sheetCount;
}
