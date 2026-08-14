package com.huang.demo.file.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadTask {

    private Long id;

    private String uploadId;

    private String fileId;

    private String ownerId;

    private String uploadType;

    private String originalName;

    private String objectKey;

    private String partObjectPrefix;

    private String bucketName;

    private String contentType;

    private Long fileSize;

    private String fileMd5;

    private String fileExt;

    private String status;

    private Long partSize;

    private Integer partCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
