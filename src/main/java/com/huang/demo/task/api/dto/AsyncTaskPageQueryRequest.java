package com.huang.demo.task.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

@Getter
@Setter
public class AsyncTaskPageQueryRequest {

    @Min(value = 1, message = "页码必须大于等于1")
    private Integer pageNo = 1;

    @Min(value = 1, message = "每页条数必须大于等于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 20;

    @Size(max = 32, message = "任务类型长度不能超过32")
    private String taskType;

    @Size(max = 16, message = "任务类型集合最多16个")
    private List<String> taskTypes;

    @Size(max = 32, message = "任务状态长度不能超过32")
    private String status;

    @Size(max = 16, message = "任务状态集合最多16个")
    private List<String> statuses;

    @Size(max = 128, message = "业务键长度不能超过128")
    private String businessKey;

    @Size(max = 64, message = "失败类型长度不能超过64")
    private String failureType;

    @Size(max = 128, message = "关键字长度不能超过128")
    private String keyword;

    private LocalDateTime createdFrom;

    private LocalDateTime createdTo;

    @Min(value = 0, message = "最小进度必须大于等于0")
    @Max(value = 100, message = "最小进度不能超过100")
    private Integer minProgress;

    @Min(value = 0, message = "最大进度必须大于等于0")
    @Max(value = 100, message = "最大进度不能超过100")
    private Integer maxProgress;

    private Boolean retryable;

    @Size(max = 64, message = "排序字段长度不能超过64")
    private String sortBy;

    @Size(max = 16, message = "排序方向长度不能超过16")
    private String sortDirection;
}
