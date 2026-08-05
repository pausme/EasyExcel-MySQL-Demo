package com.huang.demo.file.api.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PartUploadUrlResponse {

    private final int partNumber;

    private final String objectKey;

    private final String uploadUrl;

    private final long expectedSize;
}
