package com.huang.demo.file.api.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Getter
@Setter
public class FileReferenceRequest {

    @NotBlank(message = "引用类型不能为空")
    @Size(max = 64, message = "引用类型长度不能超过64")
    private String referenceType;

    @NotBlank(message = "引用标识不能为空")
    @Size(max = 128, message = "引用标识长度不能超过128")
    private String referenceId;
}
