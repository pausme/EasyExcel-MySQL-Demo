package com.huang.demo.common.audit.repository;

import com.huang.demo.common.audit.domain.entity.DownloadAuditRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DownloadAuditRecordMapper {

    void createTableIfAbsent();

    int insert(DownloadAuditRecord record);

    long countPage(@Param("ownerId") String ownerId,
                   @Param("resourceType") String resourceType,
                   @Param("resourceId") String resourceId,
                   @Param("createdFrom") LocalDateTime createdFrom,
                   @Param("createdTo") LocalDateTime createdTo);

    List<DownloadAuditRecord> listPage(@Param("ownerId") String ownerId,
                                       @Param("resourceType") String resourceType,
                                       @Param("resourceId") String resourceId,
                                       @Param("createdFrom") LocalDateTime createdFrom,
                                       @Param("createdTo") LocalDateTime createdTo,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);
}
