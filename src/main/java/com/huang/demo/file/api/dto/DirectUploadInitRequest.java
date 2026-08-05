package com.huang.demo.file.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DirectUploadInitRequest {

    private String originalName;

    private String contentType;

    private Long fileSize;

    private String fileMd5;
}
