package com.huang.demo.excel.listener;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.huang.demo.excel.domain.model.StudentImportBatch;
import com.huang.demo.excel.domain.model.StudentImportProgressCallback;
import com.huang.demo.excel.model.StudentExcelRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class StudentImportListener extends AnalysisEventListener<StudentExcelRow> {

    private static final Logger log = LoggerFactory.getLogger(StudentImportListener.class);

    private final int batchSize;
    private final BlockingQueue<StudentImportBatch> importQueue;
    private final AtomicReference<Throwable> workerFailure;
    private final int progressLogInterval;
    private final StudentImportProgressCallback progressCallback;
    private final List<StudentExcelRow> cache = new ArrayList<StudentExcelRow>();

    private int parsedCount;
    private int batchCount;

    public StudentImportListener(int batchSize,
                                 BlockingQueue<StudentImportBatch> importQueue,
                                 AtomicReference<Throwable> workerFailure,
                                 int progressLogInterval) {
        this(batchSize, importQueue, workerFailure, progressLogInterval, StudentImportProgressCallback.NONE);
    }

    public StudentImportListener(int batchSize,
                                 BlockingQueue<StudentImportBatch> importQueue,
                                 AtomicReference<Throwable> workerFailure,
                                 int progressLogInterval,
                                 StudentImportProgressCallback progressCallback) {
        this.batchSize = batchSize;
        this.importQueue = importQueue;
        this.workerFailure = workerFailure;
        this.progressLogInterval = progressLogInterval;
        this.progressCallback = progressCallback == null ? StudentImportProgressCallback.NONE : progressCallback;
    }

    @Override
    public void invoke(StudentExcelRow data, AnalysisContext context) {
        progressCallback.checkCanceled();
        throwIfWorkerFailed();
        cache.add(data);
        if (cache.size() >= batchSize) {
            flush();
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        progressCallback.checkCanceled();
        throwIfWorkerFailed();
        flush();
    }

    public int getParsedCount() {
        return parsedCount;
    }

    public int getBatchCount() {
        return batchCount;
    }

    private void flush() {
        if (cache.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        List<StudentExcelRow> batch = new ArrayList<StudentExcelRow>(cache);
        enqueue(new StudentImportBatch(parsedCount + 1, batch));
        parsedCount += batch.size();
        batchCount++;
        progressCallback.onParsed(parsedCount, batchCount);
        cache.clear();
        if (shouldLogProgress()) {
            log.info("import batch queued, batchNo={}, rows={}, totalParsed={}, elapsedMs={}",
                    batchCount, batch.size(), parsedCount, System.currentTimeMillis() - start);
        }
    }

    private void enqueue(StudentImportBatch batch) {
        while (true) {
            progressCallback.checkCanceled();
            throwIfWorkerFailed();
            try {
                if (importQueue.offer(batch, 1, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("导入解析线程被中断", ex);
            }
        }
    }

    private void throwIfWorkerFailed() {
        Throwable failure = workerFailure.get();
        if (failure != null) {
            throw new IllegalStateException("导入写库线程执行失败", failure);
        }
    }

    private boolean shouldLogProgress() {
        int interval = Math.max(1, progressLogInterval);
        return batchCount == 1 || batchCount % interval == 0;
    }
}
