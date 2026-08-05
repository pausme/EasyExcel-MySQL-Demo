package com.huang.demo.file.api.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DirectUploadInitResponse {

    private final boolean instant;

    private final String uploadId;

    private final String fileId;

    private final String uploadUrl;

    private final String objectKey;

    private final Integer expireMinutes;

    private final FileResponse file;
}
