package com.huang.demo.excel.repository;

import com.huang.demo.excel.domain.entity.StudentReportRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface StudentReportRunMapper {

    void createTableIfAbsent();

    int insert(StudentReportRun run);

    int update(StudentReportRun run);

    Optional<StudentReportRun> findByRunId(@Param("runId") String runId);

    Optional<StudentReportRun> findNormalByOwnerAndCode(@Param("ownerId") String ownerId,
                                                        @Param("runControlCode") String runControlCode);

    long countByOwner(@Param("ownerId") String ownerId,
                      @Param("runName") String runName,
                      @Param("status") String status);

    List<StudentReportRun> listByOwnerPage(@Param("ownerId") String ownerId,
                                           @Param("runName") String runName,
                                           @Param("status") String status,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);
}
