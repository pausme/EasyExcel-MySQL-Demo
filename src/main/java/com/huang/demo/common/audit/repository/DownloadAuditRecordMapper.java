package com.huang.demo.common.audit.repository;

import com.huang.demo.common.audit.domain.entity.DownloadAuditRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DownloadAuditRecordMapper {

    void createTableIfAbsent();

    int insert(DownloadAuditRecord record);
}
