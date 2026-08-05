package com.huang.demo.file.api.dto;

import com.huang.demo.file.domain.entity.FileRecord;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class FileResponse {

    private final String fileId;

    private final String originalName;

    private final String contentType;

    private final Long fileSize;

    private final String fileMd5;

    private final String fileExt;

    private final String status;

    private final LocalDateTime createdAt;

    private final LocalDateTime updatedAt;

    public static FileResponse from(FileRecord record) {
        return FileResponse.builder()
                .fileId(record.getFileId())
                .originalName(record.getOriginalName())
                .contentType(record.getContentType())
                .fileSize(record.getFileSize())
                .fileMd5(record.getFileMd5())
                .fileExt(record.getFileExt())
                .status(record.getStatus())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .build();
    }
}
