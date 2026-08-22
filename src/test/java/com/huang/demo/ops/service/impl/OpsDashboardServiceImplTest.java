package com.huang.demo.ops.service.impl;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import com.huang.demo.common.compensation.repository.CompensationRecordMapper;
import com.huang.demo.file.repository.FileRecordMapper;
import com.huang.demo.ops.api.dto.OpsOverviewResponse;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskFailureType;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsDashboardServiceImplTest {

    private ThreadPoolTaskExecutor exportExecutor;
    private ThreadPoolTaskExecutor importExecutor;
    private ThreadPoolTaskExecutor importWorkerExecutor;

    @BeforeEach
    void setUp() {
        exportExecutor = executor("ops-export-");
        importExecutor = executor("ops-import-");
        importWorkerExecutor = executor("ops-import-worker-");
    }

    @AfterEach
    void tearDown() {
        exportExecutor.shutdown();
        importExecutor.shutdown();
        importWorkerExecutor.shutdown();
    }

    @Test
    void overviewAggregatesBoundedOperationalData() {
        AsyncTaskRecordMapper taskMapper = mock(AsyncTaskRecordMapper.class);
        CompensationRecordMapper compensationMapper = mock(CompensationRecordMapper.class);
        FileRecordMapper fileMapper = mock(FileRecordMapper.class);
        when(taskMapper.countCreatedAtOrAfter(any())).thenReturn(12L);
        when(taskMapper.countByStatusCreatedAtOrAfter(eq(AsyncTaskStatus.FAILED.name()), any())).thenReturn(2L);
        when(taskMapper.countByStatuses(anyList())).thenReturn(3L);
        when(compensationMapper.countByStatuses(anyList())).thenReturn(4L);
        when(fileMapper.countNormalGlobalCreatedAtOrAfter(any())).thenReturn(5L);
        when(fileMapper.sumNormalFileSizeGlobal()).thenReturn(1024L);
        when(taskMapper.listRecentByStatuses(anyList(), eq(10)))
                .thenReturn(Collections.singletonList(failedTask()));
        when(compensationMapper.listRecentByStatuses(anyList(), eq(10)))
                .thenReturn(Collections.singletonList(compensation()));
        OpsDashboardServiceImpl service = new OpsDashboardServiceImpl(
                taskMapper, compensationMapper, fileMapper,
                exportExecutor, importExecutor, importWorkerExecutor);

        OpsOverviewResponse response = service.overview();

        assertNotNull(response.getGeneratedAt());
        assertEquals(12L, response.getTodayTaskCount().longValue());
        assertEquals(2L, response.getTodayFailedTaskCount().longValue());
        assertEquals(3L, response.getRunningTaskCount().longValue());
        assertEquals(4L, response.getCompensationBacklogCount().longValue());
        assertEquals(5L, response.getTodayFileUploadCount().longValue());
        assertEquals(1024L, response.getTotalFileStorageBytes().longValue());
        assertEquals(3, response.getThreadPools().size());
        assertEquals("task-1", response.getRecentFailedTasks().get(0).getTaskId());
        assertEquals("comp-1", response.getRecentCompensations().get(0).getCompensationId());

        ArgumentCaptor<List> statusesCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper).listRecentByStatuses(statusesCaptor.capture(), eq(10));
        assertEquals(AsyncTaskStatus.FAILED.name(), statusesCaptor.getValue().get(0));
    }

    private AsyncTaskRecord failedTask() {
        LocalDateTime now = LocalDateTime.now();
        return AsyncTaskRecord.builder()
                .taskId("task-1")
                .ownerId("user-1")
                .taskType(AsyncTaskType.EXPORT.name())
                .taskName("导出")
                .status(AsyncTaskStatus.FAILED.name())
                .progressPercent(50)
                .totalCount(100L)
                .completedCount(50L)
                .retryCount(1)
                .maxRetryCount(3)
                .errorMessage("failed")
                .failureType(AsyncTaskFailureType.DEPENDENCY_ERROR.name())
                .retryable(true)
                .createdAt(now.minusMinutes(2))
                .updatedAt(now.minusMinutes(1))
                .startedAt(now.minusMinutes(2))
                .finishedAt(now.minusMinutes(1))
                .expireAt(now.plusHours(1))
                .build();
    }

    private CompensationRecord compensation() {
        LocalDateTime now = LocalDateTime.now();
        return CompensationRecord.builder()
                .compensationId("comp-1")
                .bizType("EXPORT")
                .bizId("task-1")
                .failureType("OBJECT_MISSING")
                .status("PENDING")
                .retryCount(0)
                .maxRetryCount(3)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private ThreadPoolTaskExecutor executor(String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();
        return executor;
    }
}
