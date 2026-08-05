package com.huang.demo.file.repository;

import com.huang.demo.file.domain.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface FileRecordMapper {

    void createTableIfAbsent();

    int insert(FileRecord record);

    Optional<FileRecord> findNormalByFileId(@Param("fileId") String fileId);

    Optional<FileRecord> findNormalByMd5AndSize(@Param("fileMd5") String fileMd5, @Param("fileSize") long fileSize);

    int markDeleted(@Param("fileId") String fileId);

    long countNormal(@Param("originalName") String originalName, @Param("fileExt") String fileExt);

    List<FileRecord> listNormalPage(@Param("originalName") String originalName,
                                    @Param("fileExt") String fileExt,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);
}
