package com.huang.demo.common.exception;

import org.springframework.http.HttpStatus;

public enum SecurityErrorCode implements ErrorCode {

    UNAUTHORIZED("SECURITY_UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("SECURITY_FORBIDDEN", "无权访问", HttpStatus.FORBIDDEN);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    SecurityErrorCode(String code, String message, HttpStatus httpStatus) {
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
