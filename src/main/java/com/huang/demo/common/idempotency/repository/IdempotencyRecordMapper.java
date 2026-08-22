package com.huang.demo.common.idempotency.repository;

import com.huang.demo.common.idempotency.domain.entity.IdempotencyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface IdempotencyRecordMapper {

    void createTableIfAbsent();

    int insert(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByKey(@Param("ownerId") String ownerId,
                                          @Param("operation") String operation,
                                          @Param("idempotencyKey") String idempotencyKey);

    int markSuccess(@Param("id") Long id,
                    @Param("responsePayload") String responsePayload,
                    @Param("updatedAt") LocalDateTime updatedAt);

    int markFailed(@Param("id") Long id,
                   @Param("errorMessage") String errorMessage,
                   @Param("updatedAt") LocalDateTime updatedAt);

    int tryReclaimStaleProcessing(@Param("id") Long id,
                                  @Param("staleBefore") LocalDateTime staleBefore,
                                  @Param("updatedAt") LocalDateTime updatedAt);
}
