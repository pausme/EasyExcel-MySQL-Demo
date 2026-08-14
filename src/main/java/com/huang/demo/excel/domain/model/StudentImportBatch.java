package com.huang.demo.excel.domain.model;

import com.huang.demo.excel.model.StudentExcelRow;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class StudentImportBatch {

    private final int startRowNo;

    private final List<StudentExcelRow> rows;
}
