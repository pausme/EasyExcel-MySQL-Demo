package com.huang.demo.file.api.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

@Getter
@Setter
public class FilePageQueryRequest {

    @Min(value = 1, message = "页码必须大于等于1")
    private Integer pageNo = 1;

    @Min(value = 1, message = "每页条数必须大于等于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 20;

    @Size(max = 255, message = "原始文件名长度不能超过255")
    private String originalName;

    @Size(max = 32, message = "文件扩展名长度不能超过32")
    private String fileExt;

    @Size(max = 16, message = "文件扩展名集合最多16个")
    private List<String> fileExts;

    @Size(max = 64, message = "文件 MD5 长度不能超过64")
    private String fileMd5;

    @Size(max = 32, message = "文件状态长度不能超过32")
    private String status;

    @Size(max = 32, message = "上传类型长度不能超过32")
    private String uploadType;

    @Size(max = 64, message = "业务类型长度不能超过64")
    private String bizType;

    @Size(max = 128, message = "业务 ID 长度不能超过128")
    private String bizId;

    @Size(max = 32, message = "标签最多32个")
    private List<String> tags;

    @Min(value = 0, message = "最小文件大小必须大于等于0")
    private Long minFileSize;

    @Min(value = 0, message = "最大文件大小必须大于等于0")
    private Long maxFileSize;

    private LocalDateTime createdFrom;

    private LocalDateTime createdTo;

    @Size(max = 64, message = "排序字段长度不能超过64")
    private String sortBy;

    @Size(max = 16, message = "排序方向长度不能超过16")
    private String sortDirection;
}
