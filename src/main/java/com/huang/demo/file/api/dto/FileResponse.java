package com.huang.demo.file.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.huang.demo.file.domain.entity.FileRecord;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@JsonDeserialize(builder = FileResponse.FileResponseBuilder.class)
public class FileResponse {

    private final String fileId;

    private final String ownerId;

    private final String bizType;

    private final String bizId;

    private final String originalName;

    private final String contentType;

    private final Long fileSize;

    private final String fileMd5;

    private final String fileExt;

    private final String status;

    private final String uploadType;

    private final List<String> tags;

    private final Integer referenceCount;

    private final LocalDateTime createdAt;

    private final LocalDateTime updatedAt;

    public static FileResponse from(FileRecord record) {
        return FileResponse.builder()
                .fileId(record.getFileId())
                .ownerId(record.getOwnerId())
                .bizType(record.getBizType())
                .bizId(record.getBizId())
                .originalName(record.getOriginalName())
                .contentType(record.getContentType())
                .fileSize(record.getFileSize())
                .fileMd5(record.getFileMd5())
                .fileExt(record.getFileExt())
                .status(record.getStatus())
                .uploadType(record.getUploadType())
                .tags(parseTags(record.getTags()))
                .referenceCount(record.getReferenceCount() == null ? 0 : record.getReferenceCount())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }

    private static List<String> parseTags(String tags) {
        if (tags == null || tags.trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<String> result = new java.util.ArrayList<String>();
        for (String tag : tags.split(",")) {
            if (tag != null && !tag.trim().isEmpty()) {
                result.add(tag.trim());
            }
        }
        return result;
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class FileResponseBuilder {
    }
}
