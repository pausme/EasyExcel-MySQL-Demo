package com.huang.demo.file.repository;

import com.huang.demo.file.domain.entity.FileUploadTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface FileUploadTaskMapper {

    void createTableIfAbsent();

    int insert(FileUploadTask task);

    Optional<FileUploadTask> findByUploadId(@Param("ownerId") String ownerId,
                                            @Param("uploadId") String uploadId);

    int markSuccess(@Param("ownerId") String ownerId,
                    @Param("uploadId") String uploadId);

    int markAborted(@Param("ownerId") String ownerId,
                    @Param("uploadId") String uploadId);

    int deleteFinishedBefore(@Param("completedBefore") LocalDateTime completedBefore,
                             @Param("limit") int limit);
}
