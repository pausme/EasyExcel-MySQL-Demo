package com.huang.demo.ops.api.dto;

import com.huang.demo.common.compensation.api.dto.CompensationResponse;
import com.huang.demo.task.api.dto.AsyncTaskResponse;
import com.huang.demo.task.api.dto.ThreadPoolMetricResponse;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class OpsOverviewResponse {

    private final LocalDateTime generatedAt;

    private final Long todayTaskCount;

    private final Long todayFailedTaskCount;

    private final Long runningTaskCount;

    private final Long compensationBacklogCount;

    private final Long todayFileUploadCount;

    private final Long totalFileStorageBytes;

    private final List<ThreadPoolMetricResponse> threadPools;

    private final List<AsyncTaskResponse> recentFailedTasks;

    private final List<CompensationResponse> recentCompensations;
}
