package com.huang.demo.common.exception;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ErrorCode {

    PARAM_ERROR("COMMON_PARAM_ERROR", "请求参数错误", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("COMMON_UNAUTHORIZED", "请先登录", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("COMMON_FORBIDDEN", "无权访问", HttpStatus.FORBIDDEN),
    NOT_FOUND("COMMON_NOT_FOUND", "资源不存在", HttpStatus.NOT_FOUND),
    STATE_CONFLICT("COMMON_STATE_CONFLICT", "当前状态不允许操作", HttpStatus.CONFLICT),
    EXTERNAL_DEPENDENCY_ERROR("COMMON_EXTERNAL_DEPENDENCY_ERROR", "外部依赖调用失败", HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR("COMMON_INTERNAL_ERROR", "服务内部异常", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    CommonErrorCode(String code, String message, HttpStatus httpStatus) {
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
