package com.huang.demo.file.domain.entity;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileRecord {

    private Long id;

    private String fileId;

    private String ownerId;

    private String bizType;

    private String bizId;

    private String originalName;

    private String objectKey;

    private String bucketName;

    private String contentType;

    private Long fileSize;

    private String fileMd5;

    private String fileExt;

    private String storageType;

    private String uploadType;

    private String tags;

    private Integer referenceCount;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
