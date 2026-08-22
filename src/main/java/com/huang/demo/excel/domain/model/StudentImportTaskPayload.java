package com.huang.demo.excel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentImportTaskPayload {

    private String originalName;

    private String sourceObjectKey;

    private Long fileSize;

    private String temporaryFilePath;

    private int batchSize;

    private String importMode;
}
