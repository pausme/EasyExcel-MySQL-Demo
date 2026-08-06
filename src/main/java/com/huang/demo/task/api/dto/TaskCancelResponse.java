package com.huang.demo.task.api.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TaskCancelResponse {

    private final String taskId;

    private final boolean canceled;
}
