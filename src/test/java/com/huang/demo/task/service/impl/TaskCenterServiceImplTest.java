package com.huang.demo.task.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.task.config.TaskCenterProperties;
import com.huang.demo.task.api.dto.AsyncTaskPageQueryRequest;
import com.huang.demo.task.api.dto.AsyncTaskPageResponse;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskFailureType;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.domain.model.CreateAsyncTaskCommand;
import com.huang.demo.task.domain.model.MarkAsyncTaskFailedCommand;
import com.huang.demo.task.monitor.TaskMetricsService;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskCenterServiceImplTest {

    private AsyncTaskRecordMapper taskRecordMapper;
    private ValueOperations<String, String> valueOperations;
    private TaskCenterServiceImpl taskCenterService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskRecordMapper = mock(AsyncTaskRecordMapper.class);
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        taskCenterService = new TaskCenterServiceImpl(
                taskRecordMapper,
                stringRedisTemplate,
                objectMapper,
                new TaskCenterProperties(),
                new TaskMetricsService(new SimpleMeterRegistry()));
    }

    @Test
    void createTaskPersistsCreatedTaskAndCachesState() {
        AsyncTaskRecord record = taskCenterService.createTask(CreateAsyncTaskCommand.builder()
                .ownerId("user-1")
                .taskType(AsyncTaskType.EXPORT)
                .taskName("学生数据导出")
                .businessKey("export-1")
                .requestPayload("{}")
                .build());

        ArgumentCaptor<AsyncTaskRecord> captor = ArgumentCaptor.forClass(AsyncTaskRecord.class);
        verify(taskRecordMapper).insert(captor.capture());
        assertEquals(record.getTaskId(), captor.getValue().getTaskId());
        assertEquals("user-1", record.getOwnerId());
        assertEquals(AsyncTaskStatus.CREATED.name(), record.getStatus());
        assertEquals(0, record.getProgressPercent());
        verify(valueOperations).set(anyString(), anyString(), any());
    }

    @Test
    void createTaskRejectsWhenOwnerActiveTasksExceeded() {
        when(taskRecordMapper.countActiveByOwner("user-1")).thenReturn(10L);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> taskCenterService.createTask(CreateAsyncTaskCommand.builder()
                        .ownerId("user-1")
                        .taskType(AsyncTaskType.EXPORT)
                        .taskName("学生数据导出")
                        .businessKey("export-1")
                        .requestPayload("{}")
                        .build()));

        assertEquals("当前用户的活跃任务过多，请稍后重试", exception.getMessage());
    }

    @Test
    void createTaskRejectsWhenSystemActiveTasksExceeded() {
        when(taskRecordMapper.countActiveByOwner("user-1")).thenReturn(0L);
        when(taskRecordMapper.countActive()).thenReturn(50L);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> taskCenterService.createTask(CreateAsyncTaskCommand.builder()
                        .ownerId("user-1")
                        .taskType(AsyncTaskType.EXPORT)
                        .taskName("学生数据导出")
                        .businessKey("export-1")
                        .requestPayload("{}")
                        .build()));

        assertEquals("系统活跃任务过多，请稍后重试", exception.getMessage());
    }

    @Test
    void cancelTaskMarksActiveTaskCanceled() {
        AsyncTaskRecord record = AsyncTaskRecord.builder()
                .taskId("task-1")
                .ownerId("user-1")
                .taskType(AsyncTaskType.EXPORT.name())
                .taskName("学生数据导出")
                .status(AsyncTaskStatus.RUNNING.name())
                .progressPercent(50)
                .totalCount(100L)
                .completedCount(50L)
                .retryCount(0)
                .maxRetryCount(3)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusHours(1))
                .build();
        when(valueOperations.get("task:center:task-1")).thenReturn(null);
        when(taskRecordMapper.findByTaskId("task-1")).thenReturn(Optional.of(record));
        when(taskRecordMapper.update(any(AsyncTaskRecord.class))).thenReturn(1);

        assertTrue(taskCenterService.cancelTask("task-1", "user-1"));

        ArgumentCaptor<AsyncTaskRecord> captor = ArgumentCaptor.forClass(AsyncTaskRecord.class);
        verify(taskRecordMapper).update(captor.capture());
        assertEquals(AsyncTaskStatus.CANCELED.name(), captor.getValue().getStatus());
        assertEquals("任务已取消", captor.getValue().getErrorMessage());
        assertEquals(AsyncTaskFailureType.CANCELED.name(), captor.getValue().getFailureType());
        assertTrue(captor.getValue().getRetryable());
    }

    @Test
    void markFailedPersistsFailureClassification() {
        AsyncTaskRecord record = buildTask(AsyncTaskStatus.RUNNING);
        when(taskRecordMapper.findByTaskId("task-1")).thenReturn(Optional.of(record));
        when(taskRecordMapper.update(any(AsyncTaskRecord.class))).thenReturn(1);

        taskCenterService.markFailed(MarkAsyncTaskFailedCommand.builder()
                .taskId("task-1")
                .errorMessage("导入文件校验失败，errorRows=1")
                .resultPayload("{\"errorCount\":1}")
                .failureType(AsyncTaskFailureType.VALIDATION_ERROR)
                .retryable(false)
                .failureSuggestion("请下载错误明细，修正 Excel 后重新提交导入")
                .build());

        ArgumentCaptor<AsyncTaskRecord> captor = ArgumentCaptor.forClass(AsyncTaskRecord.class);
        verify(taskRecordMapper).update(captor.capture());
        AsyncTaskRecord failedRecord = captor.getValue();
        assertEquals(AsyncTaskStatus.FAILED.name(), failedRecord.getStatus());
        assertEquals(AsyncTaskFailureType.VALIDATION_ERROR.name(), failedRecord.getFailureType());
        assertFalse(failedRecord.getRetryable());
        assertEquals("请下载错误明细，修正 Excel 后重新提交导入", failedRecord.getFailureSuggestion());
    }

    @Test
    void markExpiredAllowsSuccessfulTaskToBecomeExpired() {
        AsyncTaskRecord record = buildTask(AsyncTaskStatus.SUCCESS);
        when(taskRecordMapper.findByTaskId("task-1")).thenReturn(Optional.of(record));
        when(taskRecordMapper.update(any(AsyncTaskRecord.class))).thenReturn(1);

        taskCenterService.markExpired("task-1", "导出文件不存在或已过期", "可重新提交导出任务");

        ArgumentCaptor<AsyncTaskRecord> captor = ArgumentCaptor.forClass(AsyncTaskRecord.class);
        verify(taskRecordMapper).update(captor.capture());
        AsyncTaskRecord expiredRecord = captor.getValue();
        assertEquals(AsyncTaskStatus.EXPIRED.name(), expiredRecord.getStatus());
        assertEquals("导出文件不存在或已过期", expiredRecord.getErrorMessage());
        assertEquals(AsyncTaskFailureType.DEPENDENCY_ERROR.name(), expiredRecord.getFailureType());
        assertTrue(expiredRecord.getRetryable());
        assertEquals("可重新提交导出任务", expiredRecord.getFailureSuggestion());
    }

    @Test
    void prepareRetryRejectsNonRetryableFailedTask() {
        AsyncTaskRecord record = buildTask(AsyncTaskStatus.FAILED);
        record.setFailureType(AsyncTaskFailureType.VALIDATION_ERROR.name());
        record.setRetryable(false);
        when(taskRecordMapper.findByTaskId("task-1")).thenReturn(Optional.of(record));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> taskCenterService.prepareRetry("task-1", "user-1"));

        assertEquals("当前任务失败类型不允许重试，failureType=VALIDATION_ERROR", exception.getMessage());
    }

    @Test
    void asyncTaskResponseExposesRetryDecision() {
        AsyncTaskRecord record = buildTask(AsyncTaskStatus.FAILED);
        record.setStartedAt(LocalDateTime.now().minusSeconds(10));
        record.setFinishedAt(LocalDateTime.now());
        record.setWorkerId("worker-1");
        record.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(1));
        record.setFailureType(AsyncTaskFailureType.VALIDATION_ERROR.name());
        record.setRetryable(false);
        record.setFailureSuggestion("请下载错误明细，修正 Excel 后重新提交导入");

        AsyncTaskResponse response = AsyncTaskResponse.from(record);

        assertEquals(AsyncTaskFailureType.VALIDATION_ERROR, response.getFailureType());
        assertFalse(response.getRetryable());
        assertFalse(response.getCanRetry());
        assertEquals("请下载错误明细，修正 Excel 后重新提交导入", response.getFailureSuggestion());
        assertEquals(3, response.getRemainingRetryCount().intValue());
        assertEquals("worker-1", response.getWorkerId());
        assertTrue(response.getDurationMs() >= 0L);
        assertTrue(response.getLifecycleEvents().size() >= 3);
    }

    @Test
    void pageMyTasksPassesExtendedFiltersToMapper() {
        AsyncTaskPageQueryRequest request = new AsyncTaskPageQueryRequest();
        request.setPageNo(2);
        request.setPageSize(5);
        request.setTaskType("export");
        request.setStatus("failed");
        request.setBusinessKey("biz-1");
        request.setFailureType("validation_error");
        request.setKeyword("学生");
        LocalDateTime createdFrom = LocalDateTime.now().minusDays(1);
        LocalDateTime createdTo = LocalDateTime.now();
        request.setCreatedFrom(createdFrom);
        request.setCreatedTo(createdTo);
        AsyncTaskRecord record = buildTask(AsyncTaskStatus.FAILED);
        when(taskRecordMapper.countByOwner("user-1", "EXPORT", "FAILED", "biz-1",
                "VALIDATION_ERROR", "学生", createdFrom, createdTo)).thenReturn(1L);
        when(taskRecordMapper.listByOwnerPage("user-1", "EXPORT", "FAILED", "biz-1",
                "VALIDATION_ERROR", "学生", createdFrom, createdTo, 5, 5))
                .thenReturn(Arrays.asList(record));

        AsyncTaskPageResponse response = taskCenterService.pageMyTasks("user-1", request);

        assertEquals(1L, response.getTotal());
        assertEquals(2, response.getPageNo());
        assertEquals(5, response.getPageSize());
        assertEquals(1, response.getRecords().size());
    }

    private AsyncTaskRecord buildTask(AsyncTaskStatus status) {
        return AsyncTaskRecord.builder()
                .taskId("task-1")
                .ownerId("user-1")
                .taskType(AsyncTaskType.EXPORT.name())
                .taskName("学生数据导出")
                .status(status.name())
                .progressPercent(50)
                .totalCount(100L)
                .completedCount(50L)
                .retryCount(0)
                .maxRetryCount(3)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusHours(1))
                .build();
    }
}
