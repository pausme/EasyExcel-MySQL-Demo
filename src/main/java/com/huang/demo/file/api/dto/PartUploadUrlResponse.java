package com.huang.demo.file.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonDeserialize(builder = PartUploadUrlResponse.PartUploadUrlResponseBuilder.class)
public class PartUploadUrlResponse {

    private final int partNumber;

    private final String objectKey;

    private final String uploadUrl;

    private final long expectedSize;

    @JsonPOJOBuilder(withPrefix = "")
    public static class PartUploadUrlResponseBuilder {
    }
}
