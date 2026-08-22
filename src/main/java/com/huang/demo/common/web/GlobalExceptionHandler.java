package com.huang.demo.common.web;

import com.huang.demo.common.api.dto.ApiResponse;
import com.huang.demo.common.api.dto.ApiFieldError;
import com.huang.demo.common.exception.BusinessException;
import com.huang.demo.common.exception.CommonErrorCode;
import com.huang.demo.common.exception.ErrorCode;
import com.huang.demo.common.exception.ErrorCodeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("business exception, code={}, message={}", errorCode.getCode(), ex.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.failed(errorCode.getCode(), ex.getMessage(),
                        ex.getBizId(), ex.getRetryable(), ex.getSuggestion()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex,
                                                                           HttpServletRequest request) {
        String message = hasText(ex.getReason()) ? ex.getReason() : CommonErrorCode.INTERNAL_ERROR.getMessage();
        ErrorCode errorCode = ErrorCodeResolver.resolve(ex.getStatus(), requestUri(request), message);
        log.warn("response status exception, status={}, message={}", ex.getStatus(), message);
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.failed(errorCode.getCode(), message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        ErrorCode errorCode = ErrorCodeResolver.resolve(HttpStatus.BAD_REQUEST, requestUri(request), ex.getMessage());
        log.warn("request argument invalid, message={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed(errorCode.getCode(), safeMessage(ex, errorCode)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                         HttpServletRequest request) {
        ErrorCode errorCode = ErrorCodeResolver.resolve(HttpStatus.BAD_REQUEST, requestUri(request), ex.getMessage());
        List<ApiFieldError> fieldErrors = new ArrayList<ApiFieldError>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(ApiFieldError.builder()
                    .field(fieldError.getField())
                    .message(fieldError.getDefaultMessage())
                    .rejectedValue(safeRejectedValue(fieldError.getRejectedValue()))
                    .build());
        }
        log.warn("request body validation failed, uri={}, fieldErrorCount={}",
                requestUri(request), fieldErrors.size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed(errorCode.getCode(), "请求参数校验失败",
                        null, false, "请根据 fieldErrors 修正请求参数", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex,
                                                                      HttpServletRequest request) {
        ErrorCode errorCode = ErrorCodeResolver.resolve(HttpStatus.BAD_REQUEST, requestUri(request), ex.getMessage());
        List<ApiFieldError> fieldErrors = new ArrayList<ApiFieldError>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fieldErrors.add(ApiFieldError.builder()
                    .field(String.valueOf(violation.getPropertyPath()))
                    .message(violation.getMessage())
                    .rejectedValue(safeRejectedValue(violation.getInvalidValue()))
                    .build());
        }
        log.warn("request parameter validation failed, uri={}, fieldErrorCount={}",
                requestUri(request), fieldErrors.size());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed(errorCode.getCode(), "请求参数校验失败",
                        null, false, "请根据 fieldErrors 修正请求参数", fieldErrors));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                               HttpServletRequest request) {
        ErrorCode errorCode = ErrorCodeResolver.resolve(HttpStatus.BAD_REQUEST, requestUri(request), ex.getMessage());
        log.warn("request parameter type mismatch, name={}, value={}, requiredType={}",
                ex.getName(), ex.getValue(), ex.getRequiredType());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed(errorCode.getCode(), "请求参数类型错误"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestPart(MissingServletRequestPartException ex,
                                                                      HttpServletRequest request) {
        ErrorCode errorCode = ErrorCodeResolver.resolve(HttpStatus.BAD_REQUEST, requestUri(request), ex.getMessage());
        log.warn("request part missing, partName={}", ex.getRequestPartName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed(errorCode.getCode(),
                        "缺少请求文件参数: " + ex.getRequestPartName()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                        HttpServletRequest request) {
        ErrorCode errorCode = ErrorCodeResolver.resolve(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, requestUri(request), ex.getMessage());
        log.warn("request media type not supported, contentType={}, supported={}",
                ex.getContentType(), ex.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.failed(errorCode.getCode(), "请求 Content-Type 不支持"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(IllegalStateException ex, HttpServletRequest request) {
        ErrorCode errorCode = ErrorCodeResolver.resolve(HttpStatus.CONFLICT, requestUri(request), ex.getMessage());
        log.warn("request state conflict, message={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failed(errorCode.getCode(), safeMessage(ex, errorCode)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failed(CommonErrorCode.INTERNAL_ERROR.getCode(),
                        CommonErrorCode.INTERNAL_ERROR.getMessage()));
    }

    private String safeMessage(Exception ex, ErrorCode errorCode) {
        return hasText(ex.getMessage()) ? ex.getMessage() : errorCode.getMessage();
    }

    private String requestUri(HttpServletRequest request) {
        return request == null ? null : request.getRequestURI();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private Object safeRejectedValue(Object rejectedValue) {
        if (rejectedValue == null) {
            return null;
        }
        String value = String.valueOf(rejectedValue);
        return value.length() > 128 ? value.substring(0, 128) : value;
    }
}
