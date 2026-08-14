package com.huang.demo.task.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huang.demo.task.config.TaskCenterProperties;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.AsyncTaskStatus;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.domain.model.CreateAsyncTaskCommand;
import com.huang.demo.task.monitor.TaskMetricsService;
import com.huang.demo.task.repository.AsyncTaskRecordMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }
}
