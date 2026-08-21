package com.huang.demo.file.repository;

import com.huang.demo.file.domain.entity.FileUploadTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
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

    long countUploadingByOwner(@Param("ownerId") String ownerId);

    long sumUploadingFileSize(@Param("ownerId") String ownerId);

    List<FileUploadTask> listUploadingBefore(@Param("createdBefore") LocalDateTime createdBefore,
                                             @Param("limit") int limit);

    int deleteById(@Param("id") Long id);

    int deleteFinishedBefore(@Param("completedBefore") LocalDateTime completedBefore,
                             @Param("limit") int limit);
}
