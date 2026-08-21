package com.huang.demo.common.compensation.repository;

import com.huang.demo.common.compensation.domain.entity.CompensationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface CompensationRecordMapper {

    void createTableIfAbsent();

    Optional<CompensationRecord> findActive(@Param("bizType") String bizType,
                                            @Param("bizId") String bizId,
                                            @Param("failureType") String failureType);

    int insert(CompensationRecord record);

    int markRunning(@Param("compensationId") String compensationId,
                    @Param("updatedAt") LocalDateTime updatedAt);

    int markSuccess(@Param("compensationId") String compensationId,
                    @Param("updatedAt") LocalDateTime updatedAt);

    int markFailed(@Param("compensationId") String compensationId,
                   @Param("lastError") String lastError,
                   @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("updatedAt") LocalDateTime updatedAt);
}
