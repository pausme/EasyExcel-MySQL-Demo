package com.huang.demo.file.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonDeserialize(builder = DirectUploadInitResponse.DirectUploadInitResponseBuilder.class)
public class DirectUploadInitResponse {

    private final boolean instant;

    private final String uploadId;

    private final String fileId;

    private final String uploadUrl;

    private final String objectKey;

    private final Integer expireMinutes;

    private final FileResponse file;

    @JsonPOJOBuilder(withPrefix = "")
    public static class DirectUploadInitResponseBuilder {
    }
}
