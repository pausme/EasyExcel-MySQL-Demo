package com.huang.demo.file.domain.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StoredFile {

    private final String objectKey;

    private final String bucketName;

    private final String fileMd5;

    private final long fileSize;
}
