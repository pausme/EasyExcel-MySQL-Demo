package com.huang.demo.task.domain.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MarkAsyncTaskFailedCommand {

    private final String taskId;

    private final String errorMessage;

    private final String resultPayload;

    private final AsyncTaskFailureType failureType;

    private final Boolean retryable;

    private final String failureSuggestion;
}
