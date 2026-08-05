package com.huang.demo.file.repository;

import com.huang.demo.file.domain.entity.FileUploadTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface FileUploadTaskMapper {

    void createTableIfAbsent();

    int insert(FileUploadTask task);

    Optional<FileUploadTask> findByUploadId(@Param("uploadId") String uploadId);

    int markSuccess(@Param("uploadId") String uploadId);

    int markAborted(@Param("uploadId") String uploadId);
}
