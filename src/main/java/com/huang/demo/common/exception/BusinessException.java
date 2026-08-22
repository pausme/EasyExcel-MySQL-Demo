package com.huang.demo.common.exception;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String bizId;
    private final Boolean retryable;
    private final String suggestion;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.bizId = null;
        this.retryable = null;
        this.suggestion = null;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.bizId = null;
        this.retryable = null;
        this.suggestion = null;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.bizId = null;
        this.retryable = null;
        this.suggestion = null;
    }

    public BusinessException(ErrorCode errorCode,
                             String message,
                             String bizId,
                             Boolean retryable,
                             String suggestion) {
        super(message);
        this.errorCode = errorCode;
        this.bizId = bizId;
        this.retryable = retryable;
        this.suggestion = suggestion;
    }

    public BusinessException(ErrorCode errorCode,
                             String message,
                             String bizId,
                             Boolean retryable,
                             String suggestion,
                             Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.bizId = bizId;
        this.retryable = retryable;
        this.suggestion = suggestion;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getBizId() {
        return bizId;
    }

    public Boolean getRetryable() {
        return retryable;
    }

    public String getSuggestion() {
        return suggestion;
    }
}
