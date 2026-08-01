package com.huang.demo.excel.service;

import com.huang.demo.excel.model.StudentExcelRow;

import java.io.OutputStream;
import java.util.List;

public interface StudentService {

    int count();

    List<StudentExcelRow> listPage(int offset, int limit);

    void saveBatch(List<StudentExcelRow> rows);

    int seedDemoData(int count);

    void writeImportTemplate(OutputStream outputStream);
}
