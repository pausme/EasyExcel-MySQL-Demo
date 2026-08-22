package com.huang.demo.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorCodeResolverTest {

    @Test
    void resolveExcelModuleCodes() {
        assertEquals(ExcelErrorCode.PARAM_ERROR,
                ErrorCodeResolver.resolve(HttpStatus.BAD_REQUEST, "/api/excel/import", "导入文件不能为空"));
        assertEquals(ExcelErrorCode.TASK_NOT_FOUND,
                ErrorCodeResolver.resolve(HttpStatus.NOT_FOUND, "/api/excel/export/task-1", "导出任务不存在"));
        assertEquals(ExcelErrorCode.FILE_NOT_FOUND,
                ErrorCodeResolver.resolve(HttpStatus.NOT_FOUND, "/api/excel/export/task-1/download", "导出文件不存在"));
        assertEquals(ExcelErrorCode.STATE_CONFLICT,
                ErrorCodeResolver.resolve(HttpStatus.CONFLICT, "/api/excel/export/task-1/download", "导出任务尚未完成"));
    }

    @Test
    void resolveFileModuleCodes() {
        assertEquals(FileErrorCode.PARAM_ERROR,
                ErrorCodeResolver.resolve(HttpStatus.BAD_REQUEST, "/api/files/direct/init", "上传参数不能为空"));
        assertEquals(FileErrorCode.UPLOAD_NOT_FOUND,
                ErrorCodeResolver.resolve(HttpStatus.NOT_FOUND, "/api/files/multipart/upload-1/resume", "上传任务不存在"));
        assertEquals(FileErrorCode.FILE_NOT_FOUND,
                ErrorCodeResolver.resolve(HttpStatus.NOT_FOUND, "/api/files/file-1", "文件不存在"));
        assertEquals(FileErrorCode.STATE_CONFLICT,
                ErrorCodeResolver.resolve(HttpStatus.CONFLICT, "/api/files/multipart/upload-1/complete", "上传任务状态不允许继续操作"));
    }

    @Test
    void resolveTaskAndSecurityCodes() {
        assertEquals(TaskErrorCode.PARAM_ERROR,
                ErrorCodeResolver.resolve(HttpStatus.BAD_REQUEST, "/api/tasks/page", "任务类型错误"));
        assertEquals(TaskErrorCode.NOT_FOUND,
                ErrorCodeResolver.resolve(HttpStatus.NOT_FOUND, "/api/tasks/task-1", "任务不存在"));
        assertEquals(SecurityErrorCode.UNAUTHORIZED,
                ErrorCodeResolver.resolve(HttpStatus.UNAUTHORIZED, "/api/excel/count", "请先登录"));
        assertEquals(SecurityErrorCode.FORBIDDEN,
                ErrorCodeResolver.resolve(HttpStatus.FORBIDDEN, "/api/excel/count", "无权访问"));
    }

    @Test
    void storageMessageHasHigherPriorityThanModulePath() {
        assertEquals(StorageErrorCode.DEPENDENCY_ERROR,
                ErrorCodeResolver.resolve(HttpStatus.CONFLICT, "/api/files/upload", "上传文件到 MinIO 失败"));
        assertEquals(StorageErrorCode.OBJECT_NOT_FOUND,
                ErrorCodeResolver.resolve(HttpStatus.NOT_FOUND, "/api/excel/export/task-1/download", "MinIO 文件不存在或已过期"));
    }
}
