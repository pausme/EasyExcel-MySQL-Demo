package com.huang.demo.task.repository;

import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AsyncTaskRecordMapper {

    void createTableIfAbsent();

    int insert(AsyncTaskRecord record);

    int update(AsyncTaskRecord record);

    Optional<AsyncTaskRecord> findByTaskId(@Param("taskId") String taskId);

    long countByOwner(@Param("ownerId") String ownerId,
                      @Param("taskType") String taskType,
                      @Param("status") String status);

    List<AsyncTaskRecord> listByOwnerPage(@Param("ownerId") String ownerId,
                                          @Param("taskType") String taskType,
                                          @Param("status") String status,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    long countByOwnerAndBusinessKey(@Param("ownerId") String ownerId,
                                    @Param("taskType") String taskType,
                                    @Param("businessKey") String businessKey,
                                    @Param("status") String status);

    List<AsyncTaskRecord> listByOwnerAndBusinessKeyPage(@Param("ownerId") String ownerId,
                                                        @Param("taskType") String taskType,
                                                        @Param("businessKey") String businessKey,
                                                        @Param("status") String status,
                                                        @Param("offset") int offset,
                                                        @Param("limit") int limit);

    int markExpiredBefore(@Param("expireBefore") LocalDateTime expireBefore);

    int updateHeartbeat(@Param("taskId") String taskId,
                        @Param("workerId") String workerId,
                        @Param("heartbeatAt") LocalDateTime heartbeatAt);

    List<AsyncTaskRecord> listRecoverable(@Param("heartbeatBefore") LocalDateTime heartbeatBefore,
                                          @Param("limit") int limit);

    int claimRecoverable(@Param("taskId") String taskId,
                         @Param("workerId") String workerId,
                         @Param("heartbeatBefore") LocalDateTime heartbeatBefore);
}
