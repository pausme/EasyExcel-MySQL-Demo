package com.huang.demo.file.repository;

import com.huang.demo.file.domain.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface FileRecordMapper {

    void createTableIfAbsent();

    int insert(FileRecord record);

    Optional<FileRecord> findNormalByFileId(@Param("ownerId") String ownerId,
                                            @Param("fileId") String fileId);

    Optional<FileRecord> findNormalByMd5AndSize(@Param("ownerId") String ownerId,
                                                @Param("fileMd5") String fileMd5,
                                                @Param("fileSize") long fileSize);

    int markDeleted(@Param("ownerId") String ownerId,
                    @Param("fileId") String fileId);

    long countNormal(@Param("ownerId") String ownerId,
                     @Param("originalName") String originalName,
                     @Param("fileExts") List<String> fileExts,
                     @Param("fileMd5") String fileMd5,
                     @Param("status") String status,
                     @Param("uploadType") String uploadType,
                     @Param("minFileSize") Long minFileSize,
                     @Param("maxFileSize") Long maxFileSize,
                     @Param("createdFrom") LocalDateTime createdFrom,
                     @Param("createdTo") LocalDateTime createdTo);

    long sumNormalFileSize(@Param("ownerId") String ownerId);

    long countNormalCreatedAtOrAfter(@Param("ownerId") String ownerId,
                                     @Param("createdAt") LocalDateTime createdAt);

    long countNormalGlobalCreatedAtOrAfter(@Param("createdAt") LocalDateTime createdAt);

    long sumNormalFileSizeGlobal();

    List<FileRecord> listNormalPage(@Param("ownerId") String ownerId,
                                    @Param("originalName") String originalName,
                                    @Param("fileExts") List<String> fileExts,
                                    @Param("fileMd5") String fileMd5,
                                    @Param("status") String status,
                                    @Param("uploadType") String uploadType,
                                    @Param("minFileSize") Long minFileSize,
                                    @Param("maxFileSize") Long maxFileSize,
                                    @Param("createdFrom") LocalDateTime createdFrom,
                                    @Param("createdTo") LocalDateTime createdTo,
                                    @Param("orderBy") String orderBy,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    List<FileRecord> listDeletedBefore(@Param("updatedBefore") LocalDateTime updatedBefore,
                                       @Param("limit") int limit);

    List<FileRecord> listAllAfterId(@Param("lastId") long lastId,
                                    @Param("limit") int limit);

    List<FileRecord> listNormalAfterId(@Param("lastId") long lastId,
                                       @Param("limit") int limit);

    int deleteById(@Param("id") Long id);
}
