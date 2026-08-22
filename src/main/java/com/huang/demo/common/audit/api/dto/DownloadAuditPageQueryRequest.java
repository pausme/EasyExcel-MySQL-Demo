package com.huang.demo.common.audit.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

@Getter
@Setter
public class DownloadAuditPageQueryRequest {

    @Min(value = 1, message = "页码必须大于等于1")
    private Integer pageNo = 1;

    @Min(value = 1, message = "每页条数必须大于等于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 20;

    @Size(max = 64, message = "用户 ID 长度不能超过64")
    private String ownerId;

    @Size(max = 32, message = "资源类型长度不能超过32")
    private String resourceType;

    @Size(max = 128, message = "资源 ID 长度不能超过128")
    private String resourceId;

    private LocalDateTime createdFrom;

    private LocalDateTime createdTo;
}
