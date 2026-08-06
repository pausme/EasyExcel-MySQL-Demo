package com.huang.demo.task.domain.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CreateAsyncTaskCommand {

    private final String ownerId;

    private final AsyncTaskType taskType;

    private final String taskName;

    private final String businessKey;

    private final String requestPayload;

    private final Integer maxRetryCount;
}
