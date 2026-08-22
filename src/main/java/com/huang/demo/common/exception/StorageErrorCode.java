package com.huang.demo.common.exception;

import org.springframework.http.HttpStatus;

public enum StorageErrorCode implements ErrorCode {

    PARAM_ERROR("STORAGE_PARAM_ERROR", "存储请求参数错误", HttpStatus.BAD_REQUEST),
    OBJECT_NOT_FOUND("STORAGE_OBJECT_NOT_FOUND", "存储对象不存在", HttpStatus.NOT_FOUND),
    DEPENDENCY_ERROR("STORAGE_DEPENDENCY_ERROR", "存储服务调用失败", HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR("STORAGE_INTERNAL_ERROR", "存储服务内部异常", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    StorageErrorCode(String code, String message, HttpStatus httpStatus) {
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
