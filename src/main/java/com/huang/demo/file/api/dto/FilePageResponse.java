package com.huang.demo.file.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class FilePageResponse {

    private final long total;

    private final int pageNo;

    private final int pageSize;

    private final List<FileResponse> records;
}
