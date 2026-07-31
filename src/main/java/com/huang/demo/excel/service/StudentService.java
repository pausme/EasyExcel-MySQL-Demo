package com.huang.demo.excel.service;

import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.model.StudentExcelRow;
import com.huang.demo.excel.repository.StudentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Service
public class StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentService.class);

    private final StudentMapper studentMapper;
    private final ExcelDemoProperties properties;
    private final TransactionTemplate transactionTemplate;

    public StudentService(StudentMapper studentMapper,
                          ExcelDemoProperties properties,
                          TransactionTemplate transactionTemplate) {
        this.studentMapper = studentMapper;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    public void init() {
        long start = System.currentTimeMillis();
        studentMapper.createTableIfAbsent();
        if (count() == 0) {
            seedDemoData(properties.getDemoSeedCount());
        }
        log.info("student service initialized, total={}, elapsedMs={}", count(), System.currentTimeMillis() - start);
    }

    public int count() {
        return studentMapper.count();
    }

    public List<StudentExcelRow> listPage(int offset, int limit) {
        long start = System.currentTimeMillis();
        List<StudentExcelRow> rows = studentMapper.listPage(offset, limit);
        log.debug("query student page, offset={}, limit={}, rows={}, elapsedMs={}",
                offset, limit, rows.size(), System.currentTimeMillis() - start);
        return rows;
    }

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
