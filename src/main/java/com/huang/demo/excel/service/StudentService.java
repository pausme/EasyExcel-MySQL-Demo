package com.huang.demo.excel.service;

import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.domain.model.StudentImportProgressCallback;
import com.huang.demo.excel.domain.model.StudentImportResult;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public interface StudentService {

    int count();

    List<StudentExcelRow> listPage(int offset, int limit);

    void saveBatch(List<StudentExcelRow> rows);

    StudentImportResult importExcel(InputStream inputStream, int batchSize);

    StudentImportResult importExcel(InputStream inputStream, int batchSize, StudentImportProgressCallback progressCallback);

    int seedDemoData(int count);

    void writeImportTemplate(OutputStream outputStream);
}
