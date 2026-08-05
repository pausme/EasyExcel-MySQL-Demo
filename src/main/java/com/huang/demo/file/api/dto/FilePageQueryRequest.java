package com.huang.demo.file.api.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FilePageQueryRequest {

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String originalName;

    private String fileExt;
}
