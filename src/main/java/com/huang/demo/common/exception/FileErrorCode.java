package com.huang.demo.common.exception;

import org.springframework.http.HttpStatus;

public enum FileErrorCode implements ErrorCode {

    PARAM_ERROR("FILE_PARAM_ERROR", "文件请求参数错误", HttpStatus.BAD_REQUEST),
    FILE_NOT_FOUND("FILE_NOT_FOUND", "文件不存在", HttpStatus.NOT_FOUND),
    UPLOAD_NOT_FOUND("FILE_UPLOAD_NOT_FOUND", "文件上传任务不存在", HttpStatus.NOT_FOUND),
    STATE_CONFLICT("FILE_STATE_CONFLICT", "文件状态不允许操作", HttpStatus.CONFLICT),
    STORAGE_ERROR("FILE_STORAGE_ERROR", "文件存储异常", HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR("FILE_INTERNAL_ERROR", "文件服务内部异常", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    FileErrorCode(String code, String message, HttpStatus httpStatus) {
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
