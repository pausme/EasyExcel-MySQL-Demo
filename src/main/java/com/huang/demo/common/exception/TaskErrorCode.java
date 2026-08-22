package com.huang.demo.common.exception;

import org.springframework.http.HttpStatus;

public enum TaskErrorCode implements ErrorCode {

    PARAM_ERROR("TASK_PARAM_ERROR", "任务请求参数错误", HttpStatus.BAD_REQUEST),
    NOT_FOUND("TASK_NOT_FOUND", "任务不存在", HttpStatus.NOT_FOUND),
    STATE_CONFLICT("TASK_STATE_CONFLICT", "任务状态不允许操作", HttpStatus.CONFLICT),
    RESOURCE_LIMIT("TASK_RESOURCE_LIMIT", "任务资源不足或已达限制", HttpStatus.TOO_MANY_REQUESTS),
    DEPENDENCY_ERROR("TASK_DEPENDENCY_ERROR", "任务依赖服务异常", HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR("TASK_INTERNAL_ERROR", "任务服务内部异常", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    TaskErrorCode(String code, String message, HttpStatus httpStatus) {
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
