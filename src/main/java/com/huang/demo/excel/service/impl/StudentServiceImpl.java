package com.huang.demo.excel.service.impl;

import com.alibaba.excel.EasyExcel;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.model.StudentImportBatch;
import com.huang.demo.excel.domain.model.StudentImportProgressCallback;
import com.huang.demo.excel.domain.model.StudentImportResult;
import com.huang.demo.excel.domain.model.StudentImportStageRecord;
import com.huang.demo.excel.domain.model.StudentImportValidationException;
import com.huang.demo.excel.listener.StudentImportListener;
import com.huang.demo.excel.model.StudentImportErrorRow;
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
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
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
import java.util.UUID;

@Service
public class StudentServiceImpl implements StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentServiceImpl.class);
    private static final StudentImportBatch IMPORT_POISON_BATCH =
            new StudentImportBatch(0, Collections.emptyList());

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
        validateImportTransactionTimeoutSeconds();
        validateImportWorkerFinishWaitSeconds();
        logImportResourceSummary();
        if (!properties.isInitEnabled()) {
            log.info("student service database initialization skipped");
            return;
        }
        long start = System.currentTimeMillis();
        studentMapper.createTableIfAbsent();
        studentMapper.createImportStageTableIfAbsent();
        try {
            studentMapper.updateImportStageColumnCapacity();
        } catch (RuntimeException ex) {
            log.warn("update student import stage column capacity failed", ex);
        }
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
        return importExcel(inputStream, batchSize, StudentImportProgressCallback.NONE);
    }

    public StudentImportResult importExcel(InputStream inputStream,
                                           int batchSize,
                                           StudentImportProgressCallback progressCallback) {
        long start = System.currentTimeMillis();
        acquireImportPermit();
        try {
            StudentImportProgressCallback safeProgressCallback =
                    progressCallback == null ? StudentImportProgressCallback.NONE : progressCallback;
            int importBatchSize = Math.max(1, batchSize);
            String importTaskId = UUID.randomUUID().toString().replace("-", "");
            int workerCount = Math.max(1, properties.getImportWorkerCount());
            int queueCapacity = Math.max(workerCount, properties.getImportQueueCapacity());
            BlockingQueue<StudentImportBatch> importQueue =
                    new ArrayBlockingQueue<StudentImportBatch>(queueCapacity);
            AtomicInteger stagedCount = new AtomicInteger();
            AtomicInteger stagedBatchCount = new AtomicInteger();
            AtomicReference<Throwable> workerFailure = new AtomicReference<Throwable>();
            CountDownLatch workerLatch = new CountDownLatch(workerCount);

            List<ImportWorkerHandle> workerHandles = startImportWorkers(
                    workerCount, importTaskId, importQueue, stagedCount, stagedBatchCount,
                    workerFailure, workerLatch, safeProgressCallback);
            StudentImportListener listener = new StudentImportListener(
                    importBatchSize, importQueue, workerFailure,
                    getImportProgressLogInterval(), safeProgressCallback);
            try {
                safeProgressCallback.checkCanceled();
                EasyExcel.read(inputStream, StudentExcelRow.class, listener).doReadAll();
                safeProgressCallback.checkCanceled();
                stopImportWorkers(importQueue, workerCount);
                awaitImportWorkers(workerLatch);
                awaitWorkerFutures(workerHandles);
                throwIfImportWorkerFailed(workerFailure);
                mergeImportStageAtomically(importTaskId, stagedCount.get(), safeProgressCallback);
            } catch (RuntimeException ex) {
                cleanupFailedImportWorkers(importQueue, workerCount, workerHandles, workerLatch);
                throw ex;
            } finally {
                deleteImportStageQuietly(importTaskId);
            }

            StudentImportResult result = StudentImportResult.builder()
                    .importedCount(stagedCount.get())
                    .batchCount(stagedBatchCount.get())
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
                              String importTaskId,
                              BlockingQueue<StudentImportBatch> importQueue,
                              AtomicInteger stagedCount,
                              AtomicInteger stagedBatchCount,
                              AtomicReference<Throwable> workerFailure,
                              StudentImportProgressCallback progressCallback) {
        try {
            while (true) {
                progressCallback.checkCanceled();
                StudentImportBatch batch = importQueue.take();
                if (batch == IMPORT_POISON_BATCH) {
                    return;
                }
                if (workerFailure.get() != null) {
                    continue;
                }
                long start = System.currentTimeMillis();
                try {
                    int chunkCount = stageImportBatchInTransactionWithRetry(
                            importTaskId, batch, "import-worker-" + workerNo);
                    int batchRows = batch.getRows().size();
                    int totalStaged = stagedCount.addAndGet(batchRows);
                    int totalBatchCount = stagedBatchCount.incrementAndGet();
                    progressCallback.onCommitted(totalStaged, totalBatchCount);
                    log.info("import worker batch staged, workerNo={}, batchRows={}, chunkCount={}, totalStaged={}, totalBatchCount={}, elapsedMs={}",
                            workerNo, batchRows, chunkCount, totalStaged, totalBatchCount,
                            System.currentTimeMillis() - start);
                } catch (RuntimeException ex) {
                    workerFailure.compareAndSet(null, ex);
                    log.error("import worker batch stage failed, workerNo={}, batchRows={}, elapsedMs={}",
                            workerNo, batch.getRows().size(), System.currentTimeMillis() - start, ex);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            workerFailure.compareAndSet(null, ex);
        }
    }

    private List<ImportWorkerHandle> startImportWorkers(int workerCount,
                                                        String importTaskId,
                                                        BlockingQueue<StudentImportBatch> importQueue,
                                                        AtomicInteger stagedCount,
                                                        AtomicInteger stagedBatchCount,
                                                        AtomicReference<Throwable> workerFailure,
                                                        CountDownLatch workerLatch,
                                                        StudentImportProgressCallback progressCallback) {
        List<ImportWorkerHandle> workerHandles = new ArrayList<ImportWorkerHandle>(workerCount);
        try {
            for (int workerIndex = 1; workerIndex <= workerCount; workerIndex++) {
                final int workerNo = workerIndex;
                ImportWorkerHandle workerHandle = new ImportWorkerHandle(workerLatch);
                Future<?> future = importWorkerExecutor.submit(() -> {
                    workerHandle.markStarted();
                    try {
                        importWorker(workerNo, importTaskId, importQueue, stagedCount, stagedBatchCount,
                                workerFailure, progressCallback);
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

    private void stopImportWorkers(BlockingQueue<StudentImportBatch> importQueue, int workerCount) {
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
            int waitSeconds = properties.getImportWorkerFinishWaitSeconds();
            if (waitSeconds <= 0) {
                workerLatch.await();
                return;
            }
            boolean finished = workerLatch.await(waitSeconds, TimeUnit.SECONDS);
            if (!finished) {
                throw new IllegalStateException("等待导入写库线程结束超时，waitSeconds=" + waitSeconds);
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

    private void cleanupFailedImportWorkers(BlockingQueue<StudentImportBatch> importQueue,
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

    private void validateImportTransactionTimeoutSeconds() {
        if (properties.getImportTransactionTimeoutSeconds() <= 0) {
            throw new IllegalStateException("IMPORT_TRANSACTION_TIMEOUT_SECONDS 必须大于 0");
        }
    }

    private void validateImportWorkerFinishWaitSeconds() {
        int waitSeconds = properties.getImportWorkerFinishWaitSeconds();
        if (waitSeconds <= 0) {
            return;
        }
        int minimumWaitSeconds = calculateMinimumImportWorkerFinishWaitSeconds();
        if (waitSeconds < minimumWaitSeconds) {
            throw new IllegalStateException("IMPORT_WORKER_FINISH_WAIT_SECONDS 不能小于单批导入事务和重试窗口，waitSeconds="
                    + waitSeconds + ", minimumWaitSeconds=" + minimumWaitSeconds);
        }
    }

    private int calculateMinimumImportWorkerFinishWaitSeconds() {
        int transactionTimeoutSeconds = properties.getImportTransactionTimeoutSeconds();
        int maxRetryTimes = Math.max(0, properties.getImportMaxRetryTimes());
        long retryBackoffMillis = Math.max(0L, properties.getImportRetryBackoffMillis());
        long retryBackoffTotalMillis = retryBackoffMillis * maxRetryTimes * (maxRetryTimes + 1L) / 2L;
        long retryBackoffTotalSeconds = (retryBackoffTotalMillis + 999L) / 1000L;
        long minimumWaitSeconds = transactionTimeoutSeconds * (maxRetryTimes + 1L) + retryBackoffTotalSeconds;
        return minimumWaitSeconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) minimumWaitSeconds;
    }

    private void logImportResourceSummary() {
        long maxJvmMemoryMb = bytesToMb(Runtime.getRuntime().maxMemory());
        int workerCount = Math.max(1, properties.getImportWorkerCount());
        int maxConcurrentTasks = Math.max(1, properties.getImportMaxConcurrentTasks());
        int totalWorkerCapacity = workerCount * maxConcurrentTasks;
        java.lang.management.OperatingSystemMXBean mxBean = ManagementFactory.getOperatingSystemMXBean();
        if (mxBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean systemMxBean =
                    (com.sun.management.OperatingSystemMXBean) mxBean;
            log.info("student import resource summary, processors={}, maxJvmMemoryMb={}, totalPhysicalMemoryMb={}, "
                            + "freePhysicalMemoryMb={}, totalSwapMb={}, freeSwapMb={}, workerCount={}, "
                            + "maxConcurrentTasks={}, totalWorkerCapacity={}, maxRowsPerTask={}, maxFileSizeBytes={}",
                    systemMxBean.getAvailableProcessors(),
                    maxJvmMemoryMb,
                    bytesToMb(systemMxBean.getTotalPhysicalMemorySize()),
                    bytesToMb(systemMxBean.getFreePhysicalMemorySize()),
                    bytesToMb(systemMxBean.getTotalSwapSpaceSize()),
                    bytesToMb(systemMxBean.getFreeSwapSpaceSize()),
                    workerCount,
                    maxConcurrentTasks,
                    totalWorkerCapacity,
                    properties.getImportMaxRowsPerTask(),
                    properties.getImportMaxFileSizeForAsync());
            return;
        }
        log.info("student import resource summary, processors={}, maxJvmMemoryMb={}, workerCount={}, "
                        + "maxConcurrentTasks={}, totalWorkerCapacity={}, maxRowsPerTask={}, maxFileSizeBytes={}",
                mxBean.getAvailableProcessors(),
                maxJvmMemoryMb,
                workerCount,
                maxConcurrentTasks,
                totalWorkerCapacity,
                properties.getImportMaxRowsPerTask(),
                properties.getImportMaxFileSizeForAsync());
    }

    private long bytesToMb(long bytes) {
        if (bytes <= 0L) {
            return 0L;
        }
        return bytes / 1024L / 1024L;
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

    private int stageImportBatchInTransactionWithRetry(String importTaskId, StudentImportBatch batch, String scene) {
        int maxRetryTimes = Math.max(0, properties.getImportMaxRetryTimes());
        long retryBackoffMillis = Math.max(0L, properties.getImportRetryBackoffMillis());
        int attempt = 0;
        while (true) {
            try {
                return stageImportBatchInTransaction(importTaskId, batch, scene);
            } catch (RuntimeException ex) {
                if (!isRetryableImportException(ex) || attempt >= maxRetryTimes) {
                    throw ex;
                }
                attempt++;
                long sleepMillis = retryBackoffMillis * attempt;
                log.warn("retry import stage batch after transient database error, scene={}, attempt={}, maxRetryTimes={}, rows={}, backoffMs={}",
                        scene, attempt, maxRetryTimes, batch.getRows().size(), sleepMillis, ex);
                sleepQuietly(sleepMillis);
            }
        }
    }

    private int stageImportBatchInTransaction(String importTaskId, StudentImportBatch batch, String scene) {
        return newImportTransactionTemplate().execute(status -> {
            List<StudentExcelRow> rows = batch.getRows();
            int batchSize = getInsertBatchSize();
            int batchCount = 0;
            for (int from = 0; from < rows.size(); from += batchSize) {
                int to = Math.min(rows.size(), from + batchSize);
                List<StudentImportStageRecord> chunk =
                        toStageRecords(importTaskId, batch.getStartRowNo() + from, rows.subList(from, to));
                batchCount++;
                long start = System.currentTimeMillis();
                studentMapper.saveImportStageBatch(chunk);
                log.debug("stage student import chunk, scene={}, batchNo={}, rows={}, elapsedMs={}",
                        scene, batchCount, chunk.size(), System.currentTimeMillis() - start);
            }
            return batchCount;
        });
    }

    private List<StudentImportStageRecord> toStageRecords(String importTaskId,
                                                          int startRowNo,
                                                          List<StudentExcelRow> rows) {
        List<StudentImportStageRecord> records = new ArrayList<StudentImportStageRecord>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            StudentExcelRow row = rows.get(i);
            records.add(StudentImportStageRecord.builder()
                    .importTaskId(importTaskId)
                    .rowNo(startRowNo + i)
                    .studentNo(row.getStudentNo())
                    .name(row.getName())
                    .age(row.getAge())
                    .gender(row.getGender())
                    .className(row.getClassName())
                    .email(row.getEmail())
                    .birthday(row.getBirthday())
                    .build());
        }
        return records;
    }

    private void mergeImportStageAtomically(String importTaskId,
                                            int expectedRows,
                                            StudentImportProgressCallback progressCallback) {
        progressCallback.checkCanceled();
        long start = System.currentTimeMillis();
        validateImportStageBeforeMerge(importTaskId, expectedRows);
        int mergeChunkSize = getImportMergeChunkSize();
        int mergeChunkCount = expectedRows == 0 ? 0 : Math.max(1, (expectedRows + mergeChunkSize - 1) / mergeChunkSize);
        int mergedRows = 0;
        for (int startRowNo = 1; startRowNo <= expectedRows; startRowNo += mergeChunkSize) {
            progressCallback.checkCanceled();
            final int chunkStartRowNo = startRowNo;
            final int chunkEndRowNo = Math.min(expectedRows, chunkStartRowNo + mergeChunkSize - 1);
            long chunkStart = System.currentTimeMillis();
            Integer affectedRows = newImportTransactionTemplate()
                    .execute(status -> studentMapper.mergeImportStageRangeToStudent(
                            importTaskId, chunkStartRowNo, chunkEndRowNo));
            mergedRows = chunkEndRowNo;
            progressCallback.onCommitted(mergedRows, mergeChunkCount);
            log.info("import stage merge chunk finished, importTaskId={}, startRowNo={}, endRowNo={}, "
                            + "affectedRows={}, elapsedMs={}",
                    importTaskId, chunkStartRowNo, chunkEndRowNo,
                    affectedRows == null ? 0 : affectedRows, System.currentTimeMillis() - chunkStart);
        }
        progressCallback.onCommitted(expectedRows, mergeChunkCount);
        log.info("import stage merged by chunks, rows={}, chunks={}, chunkSize={}, elapsedMs={}",
                expectedRows, mergeChunkCount, mergeChunkSize, System.currentTimeMillis() - start);
    }

    private void validateImportStageBeforeMerge(String importTaskId, int expectedRows) {
        int stagedRows = studentMapper.countImportStageRows(importTaskId);
        if (stagedRows != expectedRows) {
            throw new IllegalStateException("导入暂存数据数量不一致，expectedRows="
                    + expectedRows + ", stagedRows=" + stagedRows);
        }
        int invalidRows = studentMapper.countInvalidImportStageRows(importTaskId);
        if (invalidRows > 0) {
            throw new IllegalStateException("导入文件存在必填字段为空的数据，invalidRows=" + invalidRows);
        }
        int duplicateStudentNoCount = studentMapper.countDuplicateImportStageStudentNo(importTaskId);
        if (duplicateStudentNoCount > 0) {
            throw new StudentImportValidationException(
                    "导入文件校验失败，errorRows=" + duplicateStudentNoCount,
                    buildImportValidationErrors(importTaskId));
        }
        List<StudentImportErrorRow> errorRows = buildImportValidationErrors(importTaskId);
        if (!errorRows.isEmpty()) {
            throw new StudentImportValidationException(
                    "导入文件校验失败，errorRows=" + errorRows.size(), errorRows);
        }
    }

    private void deleteImportStageQuietly(String importTaskId) {
        try {
            studentMapper.deleteImportStage(importTaskId);
        } catch (RuntimeException ex) {
            log.warn("delete import stage failed, importTaskId={}", importTaskId, ex);
        }
    }

    private List<StudentImportErrorRow> buildImportValidationErrors(String importTaskId) {
        Map<Integer, ImportErrorAccumulator> errorMap = new LinkedHashMap<Integer, ImportErrorAccumulator>();
        for (StudentImportStageRecord row : studentMapper.listInvalidImportStageRows(importTaskId)) {
            appendRowValidationErrors(errorMap, row);
        }
        for (StudentImportStageRecord row : studentMapper.listDuplicateImportStageStudentNoRows(importTaskId)) {
            addImportError(errorMap, row, "文件内学号重复");
        }
        List<StudentImportErrorRow> errorRows = new ArrayList<StudentImportErrorRow>(errorMap.size());
        for (ImportErrorAccumulator accumulator : errorMap.values()) {
            errorRows.add(accumulator.toErrorRow());
        }
        return errorRows;
    }

    private void appendRowValidationErrors(Map<Integer, ImportErrorAccumulator> errorMap,
                                           StudentImportStageRecord row) {
        if (isBlank(row.getStudentNo())) {
            addImportError(errorMap, row, "学号不能为空");
        } else if (row.getStudentNo().length() > 32) {
            addImportError(errorMap, row, "学号长度不能超过32");
        }
        if (isBlank(row.getName())) {
            addImportError(errorMap, row, "姓名不能为空");
        } else if (row.getName().length() > 64) {
            addImportError(errorMap, row, "姓名长度不能超过64");
        }
        if (row.getAge() != null && (row.getAge() < 0 || row.getAge() > 150)) {
            addImportError(errorMap, row, "年龄必须在0到150之间");
        }
        if (row.getGender() != null && row.getGender().length() > 16) {
            addImportError(errorMap, row, "性别长度不能超过16");
        }
        if (row.getClassName() != null && row.getClassName().length() > 64) {
            addImportError(errorMap, row, "班级长度不能超过64");
        }
        if (row.getEmail() != null && row.getEmail().length() > 128) {
            addImportError(errorMap, row, "邮箱长度不能超过128");
        } else if (!isBlank(row.getEmail()) && !isSimpleEmail(row.getEmail())) {
            addImportError(errorMap, row, "邮箱格式不正确");
        }
        if (row.getBirthday() != null && row.getBirthday().length() > 32) {
            addImportError(errorMap, row, "生日长度不能超过32");
        }
    }

    private void addImportError(Map<Integer, ImportErrorAccumulator> errorMap,
                                StudentImportStageRecord row,
                                String errorMessage) {
        Integer rowNo = row.getRowNo();
        ImportErrorAccumulator accumulator = errorMap.get(rowNo);
        if (accumulator == null) {
            accumulator = new ImportErrorAccumulator(row);
            errorMap.put(rowNo, accumulator);
        }
        accumulator.addError(errorMessage);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isSimpleEmail(String value) {
        String trimmed = value == null ? "" : value.trim();
        int atIndex = trimmed.indexOf('@');
        int dotIndex = trimmed.lastIndexOf('.');
        return atIndex > 0 && dotIndex > atIndex + 1 && dotIndex < trimmed.length() - 1;
    }

    private static class ImportErrorAccumulator {

        private final StudentImportStageRecord row;
        private final StringJoiner errorMessages = new StringJoiner("; ");

        private ImportErrorAccumulator(StudentImportStageRecord row) {
            this.row = row;
        }

        private void addError(String errorMessage) {
            errorMessages.add(errorMessage);
        }

        private StudentImportErrorRow toErrorRow() {
            return StudentImportErrorRow.builder()
                    .rowNo(row.getRowNo())
                    .studentNo(row.getStudentNo())
                    .name(row.getName())
                    .age(row.getAge())
                    .gender(row.getGender())
                    .className(row.getClassName())
                    .email(row.getEmail())
                    .birthday(row.getBirthday())
                    .errorMessage(errorMessages.toString())
                    .build();
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

    private int getImportMergeChunkSize() {
        int chunkSize = properties.getImportMergeChunkSize();
        if (chunkSize <= 0) {
            return getInsertBatchSize();
        }
        return Math.max(1, Math.min(20000, chunkSize));
    }

    private int getImportProgressLogInterval() {
        return Math.max(1, properties.getImportProgressLogInterval());
    }

    private TransactionTemplate newTransactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private TransactionTemplate newImportTransactionTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setTimeout(properties.getImportTransactionTimeoutSeconds());
        return template;
    }
}
