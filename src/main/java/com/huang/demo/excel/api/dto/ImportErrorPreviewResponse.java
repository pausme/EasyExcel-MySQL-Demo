package com.huang.demo.excel.api.dto;

import com.huang.demo.excel.domain.model.StudentImportTaskResult;
import com.huang.demo.excel.model.StudentImportErrorRow;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class ImportErrorPreviewResponse {

    private final String taskId;

    private final Integer errorCount;

    private final Boolean hasErrorFile;

    private final Map<String, Integer> errorSummary;

    private final List<StudentImportErrorRow> rows;

    public static ImportErrorPreviewResponse from(String taskId, StudentImportTaskResult result, int limit) {
        int safeLimit = Math.max(0, limit);
        List<StudentImportErrorRow> previewRows = result == null || result.getErrorPreviewRows() == null
                ? Collections.<StudentImportErrorRow>emptyList()
                : result.getErrorPreviewRows();
        if (previewRows.size() > safeLimit) {
            previewRows = previewRows.subList(0, safeLimit);
        }
        return ImportErrorPreviewResponse.builder()
                .taskId(taskId)
                .errorCount(result == null ? 0 : result.getErrorCount())
                .hasErrorFile(result != null
                        && result.getErrorObjectKey() != null
                        && !result.getErrorObjectKey().trim().isEmpty())
                .errorSummary(result == null || result.getErrorSummary() == null
                        ? Collections.<String, Integer>emptyMap()
                        : result.getErrorSummary())
                .rows(previewRows)
                .build();
    }
}
