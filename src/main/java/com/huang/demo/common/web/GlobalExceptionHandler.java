package com.huang.demo.common.web;

import com.huang.demo.common.api.dto.ApiResponse;
import com.huang.demo.common.exception.BusinessException;
import com.huang.demo.common.exception.CommonErrorCode;
import com.huang.demo.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("business exception, code={}, message={}", errorCode.getCode(), ex.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.failed(errorCode.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex) {
        CommonErrorCode errorCode = resolveByStatus(ex.getStatus());
        String message = hasText(ex.getReason()) ? ex.getReason() : errorCode.getMessage();
        log.warn("response status exception, status={}, message={}", ex.getStatus(), message);
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.failed(errorCode.getCode(), message));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        log.warn("request argument invalid, message={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed(CommonErrorCode.PARAM_ERROR.getCode(), safeMessage(ex)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("request parameter type mismatch, name={}, value={}, requiredType={}",
                ex.getName(), ex.getValue(), ex.getRequiredType());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed(CommonErrorCode.PARAM_ERROR.getCode(), "请求参数类型错误"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestPart(MissingServletRequestPartException ex) {
        log.warn("request part missing, partName={}", ex.getRequestPartName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failed(CommonErrorCode.PARAM_ERROR.getCode(),
                        "缺少请求文件参数: " + ex.getRequestPartName()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("request media type not supported, contentType={}, supported={}",
                ex.getContentType(), ex.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.failed(CommonErrorCode.PARAM_ERROR.getCode(), "请求 Content-Type 不支持"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(IllegalStateException ex) {
        log.warn("request state conflict, message={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failed(CommonErrorCode.STATE_CONFLICT.getCode(), safeMessage(ex)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        log.error("unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failed(CommonErrorCode.INTERNAL_ERROR.getCode(),
                        CommonErrorCode.INTERNAL_ERROR.getMessage()));
    }

    private CommonErrorCode resolveByStatus(HttpStatus status) {
        if (status == HttpStatus.BAD_REQUEST) {
            return CommonErrorCode.PARAM_ERROR;
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            return CommonErrorCode.UNAUTHORIZED;
        }
        if (status == HttpStatus.FORBIDDEN) {
            return CommonErrorCode.FORBIDDEN;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return CommonErrorCode.NOT_FOUND;
        }
        if (status == HttpStatus.CONFLICT) {
            return CommonErrorCode.STATE_CONFLICT;
        }
        return CommonErrorCode.INTERNAL_ERROR;
    }

    private String safeMessage(Exception ex) {
        return hasText(ex.getMessage()) ? ex.getMessage() : CommonErrorCode.PARAM_ERROR.getMessage();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
