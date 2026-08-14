package com.huang.demo.task.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThreadPoolMetricResponse {

    private String name;

    private int corePoolSize;

    private int maxPoolSize;

    private int activeCount;

    private int poolSize;

    private int queueSize;

    private long completedTaskCount;
}
