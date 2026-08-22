package com.huang.demo.file.api.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Size;
import java.util.List;

@Getter
@Setter
public class FileMetadataUpdateRequest {

    @Size(max = 64, message = "业务类型长度不能超过64")
    private String bizType;

    @Size(max = 128, message = "业务 ID 长度不能超过128")
    private String bizId;

    @Size(max = 32, message = "标签数量不能超过32个")
    private List<String> tags;
}
