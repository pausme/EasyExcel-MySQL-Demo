package com.huang.demo.excel.repository;

import com.huang.demo.excel.domain.model.StudentExportRecord;
import com.huang.demo.excel.domain.model.StudentExportQuery;
import com.huang.demo.excel.domain.model.StudentImportStageRecord;
import com.huang.demo.excel.model.StudentExcelRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StudentMapper {

    void createTableIfAbsent();

    void createVersionControlTableIfAbsent();

    void initVersionControl();

    void ensureStudentRecordVersionColumns();

    void createImportStageTableIfAbsent();

    void updateImportStageColumnCapacity();

    int countStudentNoUniqueIndex();

    int countLegacyStudentNoUniqueIndex();

    int countStudentVersionIdIndex();

    int countStudentImportTaskIdIndex();

    int countDuplicateStudentNo();

    void dropLegacyStudentNoUniqueIndex();

    void createStudentNoUniqueIndex();

    void createStudentVersionIdIndex();

    void createStudentImportTaskIdIndex();

    Long currentStudentVersion();

    int promoteStudentVersion(@Param("expectedVersion") long expectedVersion,
                              @Param("newVersion") long newVersion);

    int count();

    List<StudentExcelRow> listPage(@Param("offset") int offset, @Param("limit") int limit);

    Long maxId();

    Long maxIdByQuery(@Param("query") StudentExportQuery query);

    Long maxIdByVersionAndQuery(@Param("version") long version,
                                @Param("query") StudentExportQuery query);

    int countByMaxId(@Param("maxId") Long maxId);

    int countByMaxIdAndQuery(@Param("maxId") Long maxId, @Param("query") StudentExportQuery query);

    int countByVersionAndMaxIdAndQuery(@Param("version") long version,
                                       @Param("maxId") Long maxId,
                                       @Param("query") StudentExportQuery query);

    List<StudentExportRecord> listByCursor(@Param("lastId") long lastId,
                                           @Param("maxId") long maxId,
                                           @Param("limit") int limit);

    List<StudentExportRecord> listByCursorAndQuery(@Param("lastId") long lastId,
                                                   @Param("maxId") long maxId,
                                                   @Param("limit") int limit,
                                                   @Param("query") StudentExportQuery query);

    List<StudentExportRecord> listByCursorAndVersionAndQuery(@Param("version") long version,
                                                             @Param("lastId") long lastId,
                                                             @Param("maxId") long maxId,
                                                             @Param("limit") int limit,
                                                             @Param("query") StudentExportQuery query);

    void saveBatch(@Param("rows") List<StudentExcelRow> rows);

    void saveImportStageBatch(@Param("rows") List<StudentImportStageRecord> rows);

    int countImportStageRows(@Param("importTaskId") String importTaskId);

    int countInvalidImportStageRows(@Param("importTaskId") String importTaskId);

    int countDuplicateImportStageStudentNo(@Param("importTaskId") String importTaskId);

    List<StudentImportStageRecord> listInvalidImportStageRows(@Param("importTaskId") String importTaskId);

    List<StudentImportStageRecord> listDuplicateImportStageStudentNoRows(@Param("importTaskId") String importTaskId);

    int mergeImportStageToStudent(@Param("importTaskId") String importTaskId);

    int mergeImportStageRangeToStudent(@Param("importTaskId") String importTaskId,
                                       @Param("startRowNo") int startRowNo,
                                       @Param("endRowNo") int endRowNo,
                                       @Param("importVersion") long importVersion);

    int deleteStudentRowsByImportTaskId(@Param("importTaskId") String importTaskId);

    int deleteExpiredStudentVersions(@Param("retainCount") int retainCount,
                                     @Param("limit") int limit);

    int deleteImportStage(@Param("importTaskId") String importTaskId);

    int deleteImportStageBefore(@Param("createdBefore") LocalDateTime createdBefore,
                                @Param("limit") int limit);
}
