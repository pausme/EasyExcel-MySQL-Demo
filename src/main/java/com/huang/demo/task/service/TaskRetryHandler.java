package com.huang.demo.task.service;

import com.huang.demo.task.domain.entity.AsyncTaskRecord;

public interface TaskRetryHandler {

    String taskType();

    AsyncTaskRecord retry(AsyncTaskRecord task, String ownerId);
}
