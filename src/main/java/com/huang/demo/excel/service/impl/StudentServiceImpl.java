package com.huang.demo.excel.service.impl;

import com.alibaba.excel.EasyExcel;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.StudentImportResult;
import com.huang.demo.excel.listener.StudentImportListener;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.repository.StudentMapper;
import com.huang.demo.excel.service.StudentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class StudentServiceImpl implements StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentServiceImpl.class);

    private final StudentMapper studentMapper;
    private final ExcelDemoProperties properties;
    private final TransactionTemplate transactionTemplate;

    public StudentServiceImpl(StudentMapper studentMapper,
                              ExcelDemoProperties properties,
                              TransactionTemplate transactionTemplate) {
        this.studentMapper = studentMapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void init() {
        if (!properties.isInitEnabled()) {
            log.info("student service database initialization skipped");
            return;
        }
        long start = System.currentTimeMillis();
        studentMapper.createTableIfAbsent();
        if (studentMapper.countStudentNoUniqueIndex() == 0) {
            int duplicateCount = studentMapper.countDuplicateStudentNo();
            if (duplicateCount > 0) {
                throw new IllegalStateException("student_no 存在重复数据，请先清理后再创建唯一索引");
            }
            studentMapper.createStudentNoUniqueIndex();
        }
        if (count() == 0) {
            seedDemoData(properties.getDemoSeedCount());
        }
        log.info("student service initialized, total={}, elapsedMs={}", count(), System.currentTimeMillis() - start);
    }

    @Override
    public int count() {
        return studentMapper.count();
    }

    @Override
    public List<StudentExcelRow> listPage(int offset, int limit) {
        long start = System.currentTimeMillis();
        List<StudentExcelRow> rows = studentMapper.listPage(offset, limit);
        log.debug("query student page, offset={}, limit={}, rows={}, elapsedMs={}",
                offset, limit, rows.size(), System.currentTimeMillis() - start);
        return rows;
    }

    @Override
    public void saveBatch(List<StudentExcelRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        int batchSize = getInsertBatchSize();
        int batchCount = 0;
        int inserted = 0;
        for (int from = 0; from < rows.size(); from += batchSize) {
            int to = Math.min(rows.size(), from + batchSize);
            List<StudentExcelRow> chunk = rows.subList(from, to);
            batchCount++;
            insertChunk(chunk, "import", batchCount);
            inserted += chunk.size();
        }
        log.info("batch inserted students, rows={}, batches={}, batchSize={}, elapsedMs={}",
                inserted, batchCount, batchSize, System.currentTimeMillis() - start);
    }

    @Override
    public StudentImportResult importExcel(InputStream inputStream, int batchSize) {
        long start = System.currentTimeMillis();
        StudentImportResult result = transactionTemplate.execute(status -> {
            StudentImportListener listener = new StudentImportListener(this, batchSize);
            EasyExcel.read(inputStream, StudentExcelRow.class, listener).doReadAll();
            return StudentImportResult.builder()
                    .importedCount(listener.getImportedCount())
                    .batchCount(listener.getBatchCount())
                    .build();
        });
        log.info("import students finished, imported={}, batchCount={}, elapsedMs={}",
                result.getImportedCount(), result.getBatchCount(), System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public int seedDemoData(int count) {
        long start = System.currentTimeMillis();
        int batchSize = getInsertBatchSize();
        int startIndex = count() + 1;
        int inserted = 0;
        int batchCount = 0;
        List<StudentExcelRow> rows = new ArrayList<StudentExcelRow>(batchSize);
        for (int i = 0; i < count; i++) {
            int index = startIndex + i;
            StudentExcelRow row = new StudentExcelRow();
            row.setStudentNo(String.format("S%06d", index));
            row.setName("学生" + index);
            row.setAge(18 + (index % 8));
            row.setGender(index % 2 == 0 ? "男" : "女");
            row.setClassName("一班");
            row.setEmail("student" + index + "@demo.com");
            row.setBirthday("2000-01-" + String.format("%02d", (index % 28) + 1));
            rows.add(row);
            if (rows.size() >= batchSize) {
                batchCount++;
                insertChunk(rows, "seed", batchCount);
                inserted += rows.size();
                rows = new ArrayList<StudentExcelRow>(batchSize);
            }
        }
        if (!rows.isEmpty()) {
            batchCount++;
            insertChunk(rows, "seed", batchCount);
            inserted += rows.size();
        }
        log.info("seeded demo students, rows={}, batches={}, batchSize={}, elapsedMs={}",
                inserted, batchCount, batchSize, System.currentTimeMillis() - start);
        return inserted;
    }

    @Override
    public void writeImportTemplate(OutputStream outputStream) {
        EasyExcel.write(outputStream, StudentExcelRow.class)
                .sheet("导入模板")
                .doWrite(Collections.emptyList());
    }

    private void insertChunk(List<StudentExcelRow> rows, String scene, int batchNo) {
        long start = System.currentTimeMillis();
        transactionTemplate.executeWithoutResult(status -> studentMapper.saveBatch(rows));
        log.debug("insert student chunk, scene={}, batchNo={}, rows={}, elapsedMs={}",
                scene, batchNo, rows.size(), System.currentTimeMillis() - start);
    }

    private int getInsertBatchSize() {
        return Math.max(1, properties.getInsertBatchSize());
    }
}
