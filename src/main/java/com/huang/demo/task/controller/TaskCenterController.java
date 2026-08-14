package com.huang.demo.task.controller;

import com.huang.demo.task.api.dto.AsyncTaskPageQueryRequest;
import com.huang.demo.task.api.dto.AsyncTaskPageResponse;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
import com.huang.demo.task.api.dto.TaskCancelResponse;
import com.huang.demo.task.api.dto.ThreadPoolMetricResponse;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.service.TaskCenterService;
import com.huang.demo.task.service.TaskOwnerResolver;
import com.huang.demo.task.service.TaskRetryHandler;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@RestController
@RequestMapping("/api/tasks")
public class TaskCenterController {

    private final TaskCenterService taskCenterService;
    private final TaskOwnerResolver taskOwnerResolver;
    private final Map<String, TaskRetryHandler> retryHandlerMap;
    private final ThreadPoolTaskExecutor exportTaskExecutor;
    private final ThreadPoolTaskExecutor importTaskExecutor;
    private final ThreadPoolTaskExecutor importWorkerExecutor;

    public TaskCenterController(TaskCenterService taskCenterService,
                                TaskOwnerResolver taskOwnerResolver,
                                List<TaskRetryHandler> retryHandlers,
                                @Qualifier("exportTaskExecutor") ThreadPoolTaskExecutor exportTaskExecutor,
                                @Qualifier("importTaskExecutor") ThreadPoolTaskExecutor importTaskExecutor,
                                @Qualifier("importWorkerExecutor") ThreadPoolTaskExecutor importWorkerExecutor) {
        this.taskCenterService = taskCenterService;
        this.taskOwnerResolver = taskOwnerResolver;
        this.retryHandlerMap = buildRetryHandlerMap(retryHandlers);
        this.exportTaskExecutor = exportTaskExecutor;
        this.importTaskExecutor = importTaskExecutor;
        this.importWorkerExecutor = importWorkerExecutor;
    }

    @ApiOperation("查询自己的异步任务详情")
    @GetMapping("/{taskId}")
    public AsyncTaskResponse detail(@PathVariable("taskId") String taskId, HttpServletRequest request) {
        AsyncTaskRecord task = findMyTask(taskId, request);
        return AsyncTaskResponse.from(task);
    }

    @ApiOperation("分页查询自己的异步任务")
    @PostMapping("/page")
    public AsyncTaskPageResponse page(@RequestBody(required = false) AsyncTaskPageQueryRequest request,
                                      HttpServletRequest httpServletRequest) {
        return taskCenterService.pageMyTasks(taskOwnerResolver.resolve(httpServletRequest), request);
    }

    @ApiOperation("取消自己的异步任务")
    @PostMapping("/{taskId}/cancel")
    public TaskCancelResponse cancel(@PathVariable("taskId") String taskId, HttpServletRequest request) {
        try {
            boolean canceled = taskCenterService.cancelTask(taskId, taskOwnerResolver.resolve(request));
            return TaskCancelResponse.builder()
                    .taskId(taskId)
                    .canceled(canceled)
                    .build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @ApiOperation("重试自己的异步任务")
    @PostMapping("/{taskId}/retry")
    public AsyncTaskResponse retry(@PathVariable("taskId") String taskId, HttpServletRequest request) {
        String ownerId = taskOwnerResolver.resolve(request);
        AsyncTaskRecord task = findMyTask(taskId, request);
        TaskRetryHandler retryHandler = retryHandlerMap.get(task.getTaskType());
        if (retryHandler == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前任务类型暂不支持重试");
        }
        try {
            return AsyncTaskResponse.from(retryHandler.retry(task, ownerId));
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @ApiOperation("查询异步任务线程池监控快照")
    @GetMapping("/metrics/thread-pools")
    public List<ThreadPoolMetricResponse> threadPoolMetrics() {
        List<ThreadPoolMetricResponse> result = new ArrayList<ThreadPoolMetricResponse>();
        result.add(toThreadPoolMetric("student-export", exportTaskExecutor));
        result.add(toThreadPoolMetric("student-import-task", importTaskExecutor));
        result.add(toThreadPoolMetric("student-import-worker", importWorkerExecutor));
        return result;
    }

    private AsyncTaskRecord findMyTask(String taskId, HttpServletRequest request) {
        String ownerId = taskOwnerResolver.resolve(request);
        AsyncTaskRecord task = taskCenterService.findTask(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
        if (!ownerId.equals(task.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        }
        return task;
    }

    private Map<String, TaskRetryHandler> buildRetryHandlerMap(List<TaskRetryHandler> retryHandlers) {
        Map<String, TaskRetryHandler> result = new HashMap<String, TaskRetryHandler>();
        if (retryHandlers == null) {
            return result;
        }
        for (TaskRetryHandler retryHandler : retryHandlers) {
            result.put(retryHandler.taskType(), retryHandler);
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
