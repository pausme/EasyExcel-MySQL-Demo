package com.huang.demo.excel.listener;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
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
    private final BlockingQueue<List<StudentExcelRow>> importQueue;
    private final AtomicReference<Throwable> workerFailure;
    private final List<StudentExcelRow> cache = new ArrayList<StudentExcelRow>();

    private int parsedCount;
    private int batchCount;

    public StudentImportListener(int batchSize,
                                 BlockingQueue<List<StudentExcelRow>> importQueue,
                                 AtomicReference<Throwable> workerFailure) {
        this.batchSize = batchSize;
        this.importQueue = importQueue;
        this.workerFailure = workerFailure;
    }

    @Override
    public void invoke(StudentExcelRow data, AnalysisContext context) {
        throwIfWorkerFailed();
        cache.add(data);
        if (cache.size() >= batchSize) {
            flush();
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
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
        enqueue(batch);
        parsedCount += batch.size();
        batchCount++;
        cache.clear();
        log.info("import batch queued, batchNo={}, rows={}, totalParsed={}, elapsedMs={}",
                batchCount, batch.size(), parsedCount, System.currentTimeMillis() - start);
    }

    private void enqueue(List<StudentExcelRow> batch) {
        while (true) {
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
}
