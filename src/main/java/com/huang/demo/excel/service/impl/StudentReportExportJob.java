package com.huang.demo.excel.service.impl;

import com.huang.demo.excel.domain.model.StudentExportQuery;
import com.huang.demo.excel.domain.model.StudentExportRecord;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.report.ReportExportJob;
import com.huang.demo.excel.report.ReportPage;
import com.huang.demo.excel.report.ReportPageCursor;
import com.huang.demo.excel.report.ReportSheetConfig;
import com.huang.demo.excel.repository.StudentMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class StudentReportExportJob implements ReportExportJob<StudentExportQuery> {

    private static final int STUDENT_SHEET_INDEX = 0;
    private static final String STUDENT_SHEET_NAME = "学生数据";

    private final StudentMapper studentMapper;

    public StudentReportExportJob(StudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    @Override
    public String buildFileName(String businessKey, StudentExportQuery params) {
        return "student-demo-" + businessKey + ".xlsx";
    }

    @Override
    public Long resolveSnapshotMaxId(StudentExportQuery params) {
        return studentMapper.maxIdByQuery(params);
    }

    @Override
    public List<ReportSheetConfig> getSheetConfigs(StudentExportQuery params) {
        return Collections.singletonList(ReportSheetConfig.builder()
                .sheetIndex(STUDENT_SHEET_INDEX)
                .sheetName(STUDENT_SHEET_NAME)
                .headClass(StudentExcelRow.class)
                .build());
    }

    @Override
    public long count(StudentExportQuery params, ReportSheetConfig sheetConfig, Long snapshotMaxId) {
        return studentMapper.countByMaxIdAndQuery(snapshotMaxId, params);
    }

    @Override
    public ReportPage queryPage(StudentExportQuery params, ReportSheetConfig sheetConfig, ReportPageCursor cursor) {
        List<StudentExportRecord> records = studentMapper.listByCursorAndQuery(
                cursor.getLastCursor(), cursor.getMaxCursor(), cursor.getPageSize(), params);
        if (records.isEmpty()) {
            return ReportPage.builder()
                    .rows(Collections.emptyList())
                    .nextCursor(cursor.getLastCursor())
                    .build();
        }
        return ReportPage.builder()
                .rows(toExcelRows(records))
                .nextCursor(records.get(records.size() - 1).getId())
                .build();
    }

    private List<StudentExcelRow> toExcelRows(List<StudentExportRecord> records) {
        List<StudentExcelRow> rows = new ArrayList<StudentExcelRow>(records.size());
        for (StudentExportRecord record : records) {
            rows.add(StudentExcelRow.builder()
                    .studentNo(record.getStudentNo())
                    .name(record.getName())
                    .age(record.getAge())
                    .gender(record.getGender())
                    .className(record.getClassName())
                    .email(record.getEmail())
                    .birthday(record.getBirthday())
                    .build());
        }
        return rows;
    }
}
