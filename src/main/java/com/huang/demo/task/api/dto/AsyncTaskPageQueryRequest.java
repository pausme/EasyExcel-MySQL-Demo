package com.huang.demo.task.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AsyncTaskPageQueryRequest {

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String taskType;

    private String status;
}
