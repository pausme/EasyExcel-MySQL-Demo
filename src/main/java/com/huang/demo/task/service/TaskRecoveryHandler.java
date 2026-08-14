package com.huang.demo.task.service;

import com.huang.demo.task.domain.entity.AsyncTaskRecord;

public interface TaskRecoveryHandler {

    String taskType();

    void recover(AsyncTaskRecord task);
}
