package com.huang.demo.task.service;

import com.huang.demo.task.api.dto.AsyncTaskPageQueryRequest;
import com.huang.demo.task.api.dto.AsyncTaskPageResponse;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import com.huang.demo.task.domain.model.CreateAsyncTaskCommand;

import java.util.Optional;
import java.util.List;

public interface TaskCenterService {

    AsyncTaskRecord createTask(CreateAsyncTaskCommand command);

    Optional<AsyncTaskRecord> findTask(String taskId);

    AsyncTaskRecord markRunning(String taskId);

    AsyncTaskRecord markRunning(String taskId, String workerId);

    AsyncTaskRecord updateProgress(String taskId, long completedCount, long totalCount, int progressPercent);

    void heartbeat(String taskId, String workerId);

    AsyncTaskRecord markSuccess(String taskId, String resultPayload);

    AsyncTaskRecord markFailed(String taskId, String errorMessage);

    AsyncTaskRecord markFailed(String taskId, String errorMessage, String resultPayload);

    boolean cancelTask(String taskId, String ownerId);

    AsyncTaskRecord prepareRetry(String taskId, String ownerId);

    AsyncTaskPageResponse pageMyTasks(String ownerId, AsyncTaskPageQueryRequest request);

    AsyncTaskPageResponse pageMyTasksByBusinessKey(String ownerId,
                                                   String taskType,
                                                   String businessKey,
                                                   AsyncTaskPageQueryRequest request);

    List<AsyncTaskRecord> listRecoverableTasks(int limit);

    boolean claimRecoverableTask(String taskId);

    String currentWorkerId();
}
