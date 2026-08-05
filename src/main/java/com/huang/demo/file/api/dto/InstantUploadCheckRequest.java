package com.huang.demo.file.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InstantUploadCheckRequest {

    private String fileMd5;

    private Long fileSize;
}
