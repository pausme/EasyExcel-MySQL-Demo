package com.huang.demo.excel.listener;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.service.StudentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class StudentImportListener extends AnalysisEventListener<StudentExcelRow> {

    private static final Logger log = LoggerFactory.getLogger(StudentImportListener.class);

    private final StudentService studentService;
    private final int batchSize;
    private final List<StudentExcelRow> cache = new ArrayList<StudentExcelRow>();

    private int importedCount;
    private int batchCount;

    public StudentImportListener(StudentService studentService, int batchSize) {
        this.studentService = studentService;
        this.batchSize = batchSize;
    }

    @Override
    public void invoke(StudentExcelRow data, AnalysisContext context) {
        cache.add(data);
        if (cache.size() >= batchSize) {
            flush();
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        flush();
    }

    public int getImportedCount() {
        return importedCount;
    }

    public int getBatchCount() {
        return batchCount;
    }

    private void flush() {
        if (cache.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        int rows = cache.size();
        studentService.saveBatch(new ArrayList<StudentExcelRow>(cache));
        importedCount += rows;
        batchCount++;
        cache.clear();
        log.info("import batch flushed, batchNo={}, rows={}, totalImported={}, elapsedMs={}",
                batchCount, rows, importedCount, System.currentTimeMillis() - start);
    }
}
