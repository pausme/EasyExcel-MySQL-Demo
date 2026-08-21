package com.huang.demo.excel.api.dto;

import com.huang.demo.excel.model.StudentImportErrorRow;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class ImportPrecheckResponse {

    private final Boolean valid;

    private final String originalName;

    private final Long fileSize;

    private final Long dataRowCount;

    private final Integer maxRows;

    private final Long maxFileSize;

    private final Integer previewLimit;

    private final Integer errorCount;

    private final List<String> messages;

    private final Map<String, Integer> errorSummary;

    private final List<StudentImportErrorRow> errorPreviewRows;

    public static ImportPrecheckResponse of(Boolean valid,
                                            String originalName,
                                            Long fileSize,
                                            Long dataRowCount,
                                            Integer maxRows,
                                            Long maxFileSize,
                                            Integer previewLimit,
                                            List<String> messages,
                                            Map<String, Integer> errorSummary,
                                            List<StudentImportErrorRow> errorPreviewRows) {
        List<String> safeMessages = messages == null ? Collections.<String>emptyList() : messages;
        Map<String, Integer> safeSummary = errorSummary == null
                ? Collections.<String, Integer>emptyMap()
                : errorSummary;
        List<StudentImportErrorRow> safeRows = errorPreviewRows == null
                ? Collections.<StudentImportErrorRow>emptyList()
                : errorPreviewRows;
        return ImportPrecheckResponse.builder()
                .valid(valid)
                .originalName(originalName)
                .fileSize(fileSize)
                .dataRowCount(dataRowCount)
                .maxRows(maxRows)
                .maxFileSize(maxFileSize)
                .previewLimit(previewLimit)
                .errorCount(safeMessages.size() + safeRows.size())
                .messages(safeMessages)
                .errorSummary(safeSummary)
                .errorPreviewRows(safeRows)
                .build();
    }
}
