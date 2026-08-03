package com.huang.demo.excel.repository;

import com.huang.demo.excel.domain.model.StudentExportRecord;
import com.huang.demo.excel.model.StudentExcelRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StudentMapper {

    void createTableIfAbsent();

    int count();

    List<StudentExcelRow> listPage(@Param("offset") int offset, @Param("limit") int limit);

    Long maxId();

    int countByMaxId(@Param("maxId") Long maxId);

    List<StudentExportRecord> listByCursor(@Param("lastId") long lastId,
                                           @Param("maxId") long maxId,
                                           @Param("limit") int limit);

    void saveBatch(@Param("rows") List<StudentExcelRow> rows);
}
