package com.huang.demo.common.api.dto;

import com.huang.demo.common.web.RequestTraceFilter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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
        return ApiResponse.<T>builder()
                .success(false)
                .code(code)
                .message(message)
                .traceId(RequestTraceFilter.currentTraceId())
                .timestamp(LocalDateTime.now())
                .build();
    }
}
