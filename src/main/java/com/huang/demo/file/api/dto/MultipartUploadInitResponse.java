package com.huang.demo.file.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class MultipartUploadInitResponse {

    private final boolean instant;

    private final String uploadId;

    private final String fileId;

    private final Long fileSize;

    private final Long partSize;

    private final Integer partCount;

    private final Integer expireMinutes;

    private final List<PartUploadUrlResponse> parts;

    private final FileResponse file;
}
