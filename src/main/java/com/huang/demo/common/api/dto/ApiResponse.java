package com.huang.demo.common.api.dto;

import com.huang.demo.common.web.RequestTraceFilter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;

    private String code;

    private String message;

    private T data;

    private String traceId;

    private String bizId;

    private Boolean retryable;

    private String suggestion;

    private List<ApiFieldError> fieldErrors;

    private LocalDateTime timestamp;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .code("SUCCESS")
                .message("success")
                .data(data)
                .traceId(RequestTraceFilter.currentTraceId())
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> ApiResponse<T> failed(String code, String message) {
        return failed(code, message, null, null, null);
    }

    public static <T> ApiResponse<T> failed(String code,
                                            String message,
                                            String bizId,
                                            Boolean retryable,
                                            String suggestion) {
        return failed(code, message, bizId, retryable, suggestion, null);
    }

    public static <T> ApiResponse<T> failed(String code,
                                            String message,
                                            String bizId,
                                            Boolean retryable,
                                            String suggestion,
                                            List<ApiFieldError> fieldErrors) {
        return ApiResponse.<T>builder()
                .success(false)
                .code(code)
                .message(message)
                .bizId(bizId)
                .retryable(retryable)
                .suggestion(suggestion)
                .fieldErrors(fieldErrors)
                .traceId(RequestTraceFilter.currentTraceId())
                .timestamp(LocalDateTime.now())
                .build();
    }
}
