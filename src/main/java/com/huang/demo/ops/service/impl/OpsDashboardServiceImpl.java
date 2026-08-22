package com.huang.demo.ops.service.impl;

import com.huang.demo.common.compensation.api.dto.CompensationResponse;
import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.repository.CompensationRecordMapper;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.ops.api.dto.OpsOverviewResponse;
import com.huang.demo.ops.service.OpsDashboardService;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
import com.huang.demo.task.api.dto.ThreadPoolMetricResponse;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

@Service
public class OpsDashboardServiceImpl implements OpsDashboardService {

    private static final int RECENT_LIMIT = 10;

    private final AsyncTaskRecordMapper taskRecordMapper;
    private final CompensationRecordMapper compensationRecordMapper;
    private final FileRecordMapper fileRecordMapper;
    private final ThreadPoolTaskExecutor exportTaskExecutor;
    private final ThreadPoolTaskExecutor importTaskExecutor;
    private final ThreadPoolTaskExecutor importWorkerExecutor;

    public OpsDashboardServiceImpl(AsyncTaskRecordMapper taskRecordMapper,
                                   CompensationRecordMapper compensationRecordMapper,
                                   FileRecordMapper fileRecordMapper,
                                   @Qualifier("exportTaskExecutor") ThreadPoolTaskExecutor exportTaskExecutor,
                                   @Qualifier("importTaskExecutor") ThreadPoolTaskExecutor importTaskExecutor,
                                   @Qualifier("importWorkerExecutor") ThreadPoolTaskExecutor importWorkerExecutor) {
        this.taskRecordMapper = taskRecordMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.fileRecordMapper = fileRecordMapper;
        this.exportTaskExecutor = exportTaskExecutor;
        this.importTaskExecutor = importTaskExecutor;
        this.importWorkerExecutor = importWorkerExecutor;
    }

    @Override
    public OpsOverviewResponse overview() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        List<String> activeStatuses = Arrays.asList(
                AsyncTaskStatus.CREATED.name(), AsyncTaskStatus.RUNNING.name());
        List<String> compensationBacklogStatuses = Arrays.asList("PENDING", "RUNNING", "FAILED");

        return OpsOverviewResponse.builder()
                .generatedAt(now)
                .todayTaskCount(taskRecordMapper.countCreatedAtOrAfter(todayStart))
                .todayFailedTaskCount(taskRecordMapper.countByStatusCreatedAtOrAfter(
                        AsyncTaskStatus.FAILED.name(), todayStart))
                .runningTaskCount(taskRecordMapper.countByStatuses(activeStatuses))
                .compensationBacklogCount(compensationRecordMapper.countByStatuses(compensationBacklogStatuses))
                .todayFileUploadCount(fileRecordMapper.countNormalGlobalCreatedAtOrAfter(todayStart))
                .totalFileStorageBytes(fileRecordMapper.sumNormalFileSizeGlobal())
                .threadPools(threadPoolMetrics())
                .recentFailedTasks(recentFailedTasks())
                .recentCompensations(recentCompensations(compensationBacklogStatuses))
                .build();
    }

    private List<ThreadPoolMetricResponse> threadPoolMetrics() {
        List<ThreadPoolMetricResponse> result = new ArrayList<ThreadPoolMetricResponse>();
        result.add(toThreadPoolMetric("student-export", exportTaskExecutor));
        result.add(toThreadPoolMetric("student-import-task", importTaskExecutor));
        result.add(toThreadPoolMetric("student-import-worker", importWorkerExecutor));
        return result;
    }

    private List<AsyncTaskResponse> recentFailedTasks() {
        List<AsyncTaskRecord> records = taskRecordMapper.listRecentByStatuses(
                Arrays.asList(AsyncTaskStatus.FAILED.name(), AsyncTaskStatus.EXPIRED.name()), RECENT_LIMIT);
        List<AsyncTaskResponse> result = new ArrayList<AsyncTaskResponse>(records.size());
        for (AsyncTaskRecord record : records) {
            result.add(AsyncTaskResponse.from(record));
        }
        return result;
    }

    private List<CompensationResponse> recentCompensations(List<String> statuses) {
        List<CompensationRecord> records = compensationRecordMapper.listRecentByStatuses(statuses, RECENT_LIMIT);
        List<CompensationResponse> result = new ArrayList<CompensationResponse>(records.size());
        for (CompensationRecord record : records) {
            result.add(CompensationResponse.from(record));
        }
        return result;
    }

    private ThreadPoolMetricResponse toThreadPoolMetric(String name, ThreadPoolTaskExecutor executor) {
        ThreadPoolExecutor threadPoolExecutor = executor.getThreadPoolExecutor();
        return ThreadPoolMetricResponse.builder()
                .name(name)
                .corePoolSize(threadPoolExecutor.getCorePoolSize())
                .maxPoolSize(threadPoolExecutor.getMaximumPoolSize())
                .activeCount(threadPoolExecutor.getActiveCount())
                .poolSize(threadPoolExecutor.getPoolSize())
                .queueSize(threadPoolExecutor.getQueue().size())
                .completedTaskCount(threadPoolExecutor.getCompletedTaskCount())
                .build();
    }
}
