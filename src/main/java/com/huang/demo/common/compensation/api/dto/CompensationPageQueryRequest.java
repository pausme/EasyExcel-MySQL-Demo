package com.huang.demo.common.compensation.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

@Getter
@Setter
public class CompensationPageQueryRequest {

    @Min(value = 1, message = "页码必须大于等于1")
    private Integer pageNo = 1;

    @Min(value = 1, message = "每页条数必须大于等于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 20;

    @Size(max = 64, message = "业务类型长度不能超过64")
    private String bizType;

    @Size(max = 128, message = "业务 ID 长度不能超过128")
    private String bizId;

    @Size(max = 64, message = "失败类型长度不能超过64")
    private String failureType;

    @Size(max = 32, message = "补偿状态长度不能超过32")
    private String status;

    @Size(max = 16, message = "补偿状态集合最多16个")
    private List<String> statuses;

    private LocalDateTime createdFrom;

    private LocalDateTime createdTo;
}
