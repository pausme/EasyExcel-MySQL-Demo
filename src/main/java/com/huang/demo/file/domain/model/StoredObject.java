package com.huang.demo.file.domain.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StoredObject {

    private final String objectKey;

    private final String contentType;

    private final long size;

    private final String etag;
}
