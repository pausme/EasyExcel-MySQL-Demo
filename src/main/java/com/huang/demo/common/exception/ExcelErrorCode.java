package com.huang.demo.common.exception;

import org.springframework.http.HttpStatus;

public enum ExcelErrorCode implements ErrorCode {

    PARAM_ERROR("EXCEL_PARAM_ERROR", "Excel 请求参数错误", HttpStatus.BAD_REQUEST),
    TASK_NOT_FOUND("EXCEL_TASK_NOT_FOUND", "Excel 任务不存在", HttpStatus.NOT_FOUND),
    FILE_NOT_FOUND("EXCEL_FILE_NOT_FOUND", "Excel 文件不存在", HttpStatus.NOT_FOUND),
    STATE_CONFLICT("EXCEL_STATE_CONFLICT", "Excel 任务状态不允许操作", HttpStatus.CONFLICT),
    STORAGE_ERROR("EXCEL_STORAGE_ERROR", "Excel 文件存储异常", HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR("EXCEL_INTERNAL_ERROR", "Excel 服务内部异常", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ExcelErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
