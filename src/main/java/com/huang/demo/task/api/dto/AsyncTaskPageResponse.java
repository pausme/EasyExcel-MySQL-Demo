package com.huang.demo.task.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class AsyncTaskPageResponse {

    private final long total;

    private final int pageNo;

    private final int pageSize;

    private final List<AsyncTaskResponse> records;
}
