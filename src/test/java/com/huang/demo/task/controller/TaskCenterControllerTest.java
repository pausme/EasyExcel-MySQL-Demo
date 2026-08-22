package com.huang.demo.task.controller;

import com.huang.demo.common.exception.BusinessException;
import com.huang.demo.common.exception.SecurityErrorCode;
import com.huang.demo.security.service.PermissionService;
import com.huang.demo.task.api.dto.ThreadPoolMetricResponse;
import com.huang.demo.task.service.TaskCenterService;
import com.huang.demo.task.service.TaskOwnerResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskCenterControllerTest {

    private ThreadPoolTaskExecutor exportExecutor;
    private ThreadPoolTaskExecutor importExecutor;
    private ThreadPoolTaskExecutor importWorkerExecutor;

    @BeforeEach
    void setUp() {
        exportExecutor = executor("test-export-");
        importExecutor = executor("test-import-");
        importWorkerExecutor = executor("test-import-worker-");
    }

    @AfterEach
    void tearDown() {
        exportExecutor.shutdown();
        importExecutor.shutdown();
        importWorkerExecutor.shutdown();
    }

    @Test
    void threadPoolMetricsRequiresAdmin() {
        PermissionService permissionService = mock(PermissionService.class);
        doThrow(new BusinessException(SecurityErrorCode.FORBIDDEN, "需要管理员权限"))
                .when(permissionService).requireAdmin();
        TaskCenterController controller = newController(permissionService);

        BusinessException exception = assertThrows(BusinessException.class, controller::threadPoolMetrics);

        assertEquals(SecurityErrorCode.FORBIDDEN, exception.getErrorCode());
        verify(permissionService).requireAdmin();
    }

    @Test
    void threadPoolMetricsReturnsExecutorSnapshotForAdmin() {
        PermissionService permissionService = mock(PermissionService.class);
        TaskCenterController controller = newController(permissionService);

        List<ThreadPoolMetricResponse> response = controller.threadPoolMetrics();

        assertEquals(3, response.size());
        assertEquals("student-export", response.get(0).getName());
        assertEquals("student-import-task", response.get(1).getName());
        assertEquals("student-import-worker", response.get(2).getName());
        verify(permissionService).requireAdmin();
    }

    private TaskCenterController newController(PermissionService permissionService) {
        return new TaskCenterController(
                mock(TaskCenterService.class),
                mock(TaskOwnerResolver.class),
                Collections.emptyList(),
                exportExecutor,
                importExecutor,
                importWorkerExecutor,
                permissionService);
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
