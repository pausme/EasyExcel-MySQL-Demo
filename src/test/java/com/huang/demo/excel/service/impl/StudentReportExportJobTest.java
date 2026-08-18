package com.huang.demo.excel.service.impl;

import com.huang.demo.excel.domain.model.StudentExportQuery;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.excel.report.ReportPageCursor;
import com.huang.demo.excel.report.ReportSheetConfig;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentReportExportJobTest {

    @Test
    void exportUsesSnapshotVersionForMaxCountAndPageQuery() {
        StudentMapper studentMapper = mock(StudentMapper.class);
        StudentReportExportJob job = new StudentReportExportJob(studentMapper);
        StudentExportQuery query = StudentExportQuery.builder()
                .snapshotVersion(200L)
                .build();
        ReportSheetConfig sheetConfig = job.getSheetConfigs(query).get(0);

        when(studentMapper.maxIdByVersionAndQuery(eq(200L), any(StudentExportQuery.class))).thenReturn(1000L);
        when(studentMapper.countByVersionAndMaxIdAndQuery(eq(200L), eq(1000L), any(StudentExportQuery.class)))
                .thenReturn(10);

        job.resolveSnapshotMaxId(query);
        job.count(query, sheetConfig, 1000L);
        job.queryPage(query, sheetConfig, ReportPageCursor.builder()
                .lastCursor(0L)
                .maxCursor(1000L)
                .pageSize(500)
                .build());

        verify(studentMapper).maxIdByVersionAndQuery(eq(200L), any(StudentExportQuery.class));
        verify(studentMapper).countByVersionAndMaxIdAndQuery(eq(200L), eq(1000L), any(StudentExportQuery.class));
        verify(studentMapper).listByCursorAndVersionAndQuery(eq(200L), eq(0L), eq(1000L), eq(500), any(StudentExportQuery.class));
    }
}
