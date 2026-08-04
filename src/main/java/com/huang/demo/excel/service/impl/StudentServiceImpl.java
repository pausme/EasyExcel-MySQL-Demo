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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class StudentServiceImpl implements StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentServiceImpl.class);
    private static final List<StudentExcelRow> IMPORT_POISON_BATCH = Collections.emptyList();

    private final StudentMapper studentMapper;
    private final ExcelDemoProperties properties;
    private final PlatformTransactionManager transactionManager;
    private final ThreadPoolTaskExecutor importWorkerExecutor;
    private final Semaphore importTaskSemaphore;

    public StudentServiceImpl(StudentMapper studentMapper,
                              ExcelDemoProperties properties,
                              PlatformTransactionManager transactionManager,
                              @Qualifier("importWorkerExecutor") ThreadPoolTaskExecutor importWorkerExecutor) {
        this.studentMapper = studentMapper;
        this.properties = properties;
        this.transactionManager = transactionManager;
        this.importWorkerExecutor = importWorkerExecutor;
        this.importTaskSemaphore = new Semaphore(Math.max(1, properties.getImportMaxConcurrentTasks()));
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
        int batchCount = saveBatchInTransactionWithRetry(rows, "import");
        log.info("batch inserted students, rows={}, batches={}, batchSize={}, elapsedMs={}",
                rows.size(), batchCount, getInsertBatchSize(), System.currentTimeMillis() - start);
    }

    @Override
    public StudentImportResult importExcel(InputStream inputStream, int batchSize) {
        long start = System.currentTimeMillis();
        acquireImportPermit();
        try {
            int importBatchSize = Math.max(1, batchSize);
            int workerCount = Math.max(1, properties.getImportWorkerCount());
            int queueCapacity = Math.max(workerCount, properties.getImportQueueCapacity());
            BlockingQueue<List<StudentExcelRow>> importQueue =
                    new ArrayBlockingQueue<List<StudentExcelRow>>(queueCapacity);
            AtomicInteger importedCount = new AtomicInteger();
            AtomicInteger importedBatchCount = new AtomicInteger();
            AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
            CountDownLatch workerLatch = new CountDownLatch(workerCount);

            List<ImportWorkerHandle> workerHandles = startImportWorkers(
                    workerCount, importQueue, importedCount, importedBatchCount, workerFailure, workerLatch);
            StudentImportListener listener = new StudentImportListener(
                    importBatchSize, importQueue, workerFailure, getImportProgressLogInterval());
            try {
                EasyExcel.read(inputStream, StudentExcelRow.class, listener).doReadAll();
                stopImportWorkers(importQueue, workerCount);
                awaitImportWorkers(workerLatch);
                awaitWorkerFutures(workerHandles);
                throwIfImportWorkerFailed(workerFailure);
            } catch (RuntimeException ex) {
                cleanupFailedImportWorkers(importQueue, workerCount, workerHandles, workerLatch);
                throw ex;
            }

            StudentImportResult result = StudentImportResult.builder()
                    .importedCount(importedCount.get())
                    .batchCount(importedBatchCount.get())
                    .build();
            log.info("import students finished, imported={}, batchCount={}, elapsedMs={}",
                    result.getImportedCount(), result.getBatchCount(), System.currentTimeMillis() - start);
            return result;
        } finally {
            importTaskSemaphore.release();
        }
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
        newTransactionTemplate().executeWithoutResult(status -> studentMapper.saveBatch(rows));
        log.debug("insert student chunk, scene={}, batchNo={}, rows={}, elapsedMs={}",
                scene, batchNo, rows.size(), System.currentTimeMillis() - start);
    }

    private int saveBatchInTransaction(List<StudentExcelRow> rows, String scene) {
        return newImportTransactionTemplate().execute(status -> {
            int batchSize = getInsertBatchSize();
            int batchCount = 0;
            for (int from = 0; from < rows.size(); from += batchSize) {
                int to = Math.min(rows.size(), from + batchSize);
                List<StudentExcelRow> chunk = rows.subList(from, to);
                batchCount++;
                long start = System.currentTimeMillis();
                studentMapper.saveBatch(chunk);
                log.debug("insert student chunk, scene={}, batchNo={}, rows={}, elapsedMs={}",
                        scene, batchCount, chunk.size(), System.currentTimeMillis() - start);
            }
            return batchCount;
        });
    }

    private void importWorker(int workerNo,
                              BlockingQueue<List<StudentExcelRow>> importQueue,
                              AtomicInteger importedCount,
                              AtomicInteger importedBatchCount,
                              AtomicReference<Throwable> workerFailure) {
        try {
            while (true) {
                List<StudentExcelRow> batch = importQueue.take();
                if (batch == IMPORT_POISON_BATCH) {
                    return;
                }
                if (workerFailure.get() != null) {
                    continue;
                }
                long start = System.currentTimeMillis();
                try {
                    int chunkCount = saveBatchInTransactionWithRetry(batch, "import-worker-" + workerNo);
                    int totalImported = importedCount.addAndGet(batch.size());
                    int totalBatchCount = importedBatchCount.incrementAndGet();
                    log.info("import worker batch committed, workerNo={}, batchRows={}, chunkCount={}, totalImported={}, totalBatchCount={}, elapsedMs={}",
                            workerNo, batch.size(), chunkCount, totalImported, totalBatchCount,
                            System.currentTimeMillis() - start);
                } catch (RuntimeException ex) {
                    workerFailure.compareAndSet(null, ex);
                    log.error("import worker batch failed, workerNo={}, batchRows={}, elapsedMs={}",
                            workerNo, batch.size(), System.currentTimeMillis() - start, ex);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            workerFailure.compareAndSet(null, ex);
        }
    }

    private List<ImportWorkerHandle> startImportWorkers(int workerCount,
                                                        BlockingQueue<List<StudentExcelRow>> importQueue,
                                                        AtomicInteger importedCount,
                                                        AtomicInteger importedBatchCount,
                                                        AtomicReference<Throwable> workerFailure,
                                                        CountDownLatch workerLatch) {
        List<ImportWorkerHandle> workerHandles = new ArrayList<ImportWorkerHandle>(workerCount);
        try {
            for (int workerIndex = 1; workerIndex <= workerCount; workerIndex++) {
                final int workerNo = workerIndex;
                ImportWorkerHandle workerHandle = new ImportWorkerHandle(workerLatch);
                Future<?> future = importWorkerExecutor.submit(() -> {
                    workerHandle.markStarted();
                    try {
                        importWorker(workerNo, importQueue, importedCount, importedBatchCount, workerFailure);
                    } finally {
                        workerHandle.markFinished();
                    }
                });
                workerHandle.setFuture(future);
                workerHandles.add(workerHandle);
            }
            return workerHandles;
        } catch (RuntimeException ex) {
            int submittedCount = workerHandles.size();
            stopImportWorkers(importQueue, submittedCount);
            cancelWorkerFutures(workerHandles);
            for (int i = submittedCount; i < workerCount; i++) {
                workerLatch.countDown();
            }
            awaitImportWorkers(workerLatch);
            throw new IllegalStateException("导入写库线程池繁忙，请稍后重试", ex);
        }
    }

    private void stopImportWorkers(BlockingQueue<List<StudentExcelRow>> importQueue, int workerCount) {
        for (int i = 0; i < workerCount; i++) {
            try {
                importQueue.put(IMPORT_POISON_BATCH);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("停止导入写库线程失败", ex);
            }
        }
    }

    private void awaitImportWorkers(CountDownLatch workerLatch) {
        try {
            boolean finished = workerLatch.await(
                    Math.max(1, properties.getImportAwaitTerminationSeconds()), TimeUnit.SECONDS);
            if (!finished) {
                throw new IllegalStateException("等待导入写库线程结束超时");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待导入写库线程结束失败", ex);
        }
    }

    private void throwIfImportWorkerFailed(AtomicReference<Throwable> workerFailure) {
        Throwable failure = workerFailure.get();
        if (failure != null) {
            throw new IllegalStateException("导入写库线程执行失败", failure);
        }
    }

    private void cleanupFailedImportWorkers(BlockingQueue<List<StudentExcelRow>> importQueue,
                                            int workerCount,
                                            List<ImportWorkerHandle> workerHandles,
                                            CountDownLatch workerLatch) {
        importQueue.clear();
        stopImportWorkers(importQueue, workerCount);
        cancelWorkerFutures(workerHandles);
        awaitImportWorkers(workerLatch);
    }

    private void cancelWorkerFutures(List<ImportWorkerHandle> workerHandles) {
        for (ImportWorkerHandle workerHandle : workerHandles) {
            workerHandle.cancel();
        }
    }

    private void awaitWorkerFutures(List<ImportWorkerHandle> workerHandles) {
        for (ImportWorkerHandle workerHandle : workerHandles) {
            try {
                workerHandle.getFuture().get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待导入写库线程结束失败", ex);
            } catch (ExecutionException ex) {
                throw new IllegalStateException("导入写库线程执行失败", ex.getCause());
            }
        }
    }

    private void acquireImportPermit() {
        if (importTaskSemaphore.tryAcquire()) {
            return;
        }
        throw new IllegalStateException("当前已有导入任务执行中，请稍后重试");
    }

    private static class ImportWorkerHandle {

        private final CountDownLatch workerLatch;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private Future<?> future;

        private ImportWorkerHandle(CountDownLatch workerLatch) {
            this.workerLatch = workerLatch;
        }

        private void setFuture(Future<?> future) {
            this.future = future;
        }

        private Future<?> getFuture() {
            return future;
        }

        private void markStarted() {
            started.set(true);
        }

        private void markFinished() {
            if (finished.compareAndSet(false, true)) {
                workerLatch.countDown();
            }
        }

        private void cancel() {
            if (future == null) {
                markFinished();
                return;
            }
            boolean cancelled = future.cancel(true);
            if (cancelled && !started.get()) {
                markFinished();
            }
        }
    }

    private int saveBatchInTransactionWithRetry(List<StudentExcelRow> rows, String scene) {
        List<StudentExcelRow> orderedRows = new ArrayList<StudentExcelRow>(rows);
        if (properties.isImportBatchSortEnabled()) {
            orderedRows.sort(Comparator.comparing(StudentExcelRow::getStudentNo, Comparator.nullsLast(String::compareTo)));
        }
        int maxRetryTimes = Math.max(0, properties.getImportMaxRetryTimes());
        long retryBackoffMillis = Math.max(0L, properties.getImportRetryBackoffMillis());
        int attempt = 0;
        while (true) {
            try {
                return saveBatchInTransaction(orderedRows, scene);
            } catch (RuntimeException ex) {
                if (!isRetryableImportException(ex) || attempt >= maxRetryTimes) {
                    throw ex;
                }
                attempt++;
                long sleepMillis = retryBackoffMillis * attempt;
                log.warn("retry import batch after transient database error, scene={}, attempt={}, maxRetryTimes={}, rows={}, backoffMs={}",
                        scene, attempt, maxRetryTimes, orderedRows.size(), sleepMillis, ex);
                sleepQuietly(sleepMillis);
            }
        }
    }

    private boolean isRetryableImportException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TransientDataAccessException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepQuietly(long sleepMillis) {
        if (sleepMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("导入重试等待被中断", ex);
        }
    }

    private int getInsertBatchSize() {
        return Math.max(1, properties.getInsertBatchSize());
    }

    private int getImportProgressLogInterval() {
        return Math.max(1, properties.getImportProgressLogInterval());
    }

    private TransactionTemplate newTransactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private TransactionTemplate newImportTransactionTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        int timeoutSeconds = properties.getImportTransactionTimeoutSeconds();
        if (timeoutSeconds > 0) {
            template.setTimeout(timeoutSeconds);
        }
        return template;
    }
}
