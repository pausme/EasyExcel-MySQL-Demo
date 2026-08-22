package com.huang.demo.task.repository;

import com.huang.demo.task.domain.entity.AsyncTaskEventLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AsyncTaskEventLogMapper {

    void createTableIfAbsent();

    int insert(AsyncTaskEventLog eventLog);

    List<AsyncTaskEventLog> listByTaskId(@Param("taskId") String taskId,
                                         @Param("limit") int limit);
}
