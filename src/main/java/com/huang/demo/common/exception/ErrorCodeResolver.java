package com.huang.demo.common.exception;

import org.springframework.http.HttpStatus;

public final class ErrorCodeResolver {

    private ErrorCodeResolver() {
    }

    public static ErrorCode resolve(HttpStatus status, String requestUri, String message) {
        if (isSecurityStatus(status)) {
            return status == HttpStatus.FORBIDDEN ? SecurityErrorCode.FORBIDDEN : SecurityErrorCode.UNAUTHORIZED;
        }
        if (isStorageMessage(message)) {
            return resolveStorage(status);
        }
        if (startsWith(requestUri, "/api/excel")) {
            return resolveExcel(status, message);
        }
        if (startsWith(requestUri, "/api/files")) {
            return resolveFile(status, message);
        }
        if (startsWith(requestUri, "/api/tasks")) {
            return resolveTask(status);
        }
        return resolveCommon(status);
    }

    public static ErrorCode resolveCommon(HttpStatus status) {
        if (status == HttpStatus.BAD_REQUEST || status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
            return CommonErrorCode.PARAM_ERROR;
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            return SecurityErrorCode.UNAUTHORIZED;
        }
        if (status == HttpStatus.FORBIDDEN) {
            return SecurityErrorCode.FORBIDDEN;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return CommonErrorCode.NOT_FOUND;
        }
        if (status == HttpStatus.CONFLICT) {
            return CommonErrorCode.STATE_CONFLICT;
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return TaskErrorCode.RESOURCE_LIMIT;
        }
        if (status == HttpStatus.BAD_GATEWAY || status == HttpStatus.GATEWAY_TIMEOUT) {
            return CommonErrorCode.EXTERNAL_DEPENDENCY_ERROR;
        }
        return CommonErrorCode.INTERNAL_ERROR;
    }

    private static ErrorCode resolveExcel(HttpStatus status, String message) {
        if (status == HttpStatus.BAD_REQUEST || status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
            return ExcelErrorCode.PARAM_ERROR;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return hasText(message) && message.contains("文件")
                    ? ExcelErrorCode.FILE_NOT_FOUND : ExcelErrorCode.TASK_NOT_FOUND;
        }
        if (status == HttpStatus.CONFLICT) {
            return ExcelErrorCode.STATE_CONFLICT;
        }
        if (status == HttpStatus.BAD_GATEWAY || status == HttpStatus.GATEWAY_TIMEOUT) {
            return ExcelErrorCode.STORAGE_ERROR;
        }
        return ExcelErrorCode.INTERNAL_ERROR;
    }

    private static ErrorCode resolveFile(HttpStatus status, String message) {
        if (status == HttpStatus.BAD_REQUEST || status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
            return FileErrorCode.PARAM_ERROR;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return hasText(message) && message.contains("上传任务")
                    ? FileErrorCode.UPLOAD_NOT_FOUND : FileErrorCode.FILE_NOT_FOUND;
        }
        if (status == HttpStatus.CONFLICT) {
            return FileErrorCode.STATE_CONFLICT;
        }
        if (status == HttpStatus.BAD_GATEWAY || status == HttpStatus.GATEWAY_TIMEOUT) {
            return FileErrorCode.STORAGE_ERROR;
        }
        return FileErrorCode.INTERNAL_ERROR;
    }

    private static ErrorCode resolveTask(HttpStatus status) {
        if (status == HttpStatus.BAD_REQUEST || status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
            return TaskErrorCode.PARAM_ERROR;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return TaskErrorCode.NOT_FOUND;
        }
        if (status == HttpStatus.CONFLICT) {
            return TaskErrorCode.STATE_CONFLICT;
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return TaskErrorCode.RESOURCE_LIMIT;
        }
        if (status == HttpStatus.BAD_GATEWAY || status == HttpStatus.GATEWAY_TIMEOUT) {
            return TaskErrorCode.DEPENDENCY_ERROR;
        }
        return TaskErrorCode.INTERNAL_ERROR;
    }

    private static ErrorCode resolveStorage(HttpStatus status) {
        if (status == HttpStatus.BAD_REQUEST) {
            return StorageErrorCode.PARAM_ERROR;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return StorageErrorCode.OBJECT_NOT_FOUND;
        }
        return StorageErrorCode.DEPENDENCY_ERROR;
    }

    private static boolean isSecurityStatus(HttpStatus status) {
        return status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN;
    }

    private static boolean isStorageMessage(String message) {
        return hasText(message)
                && (message.contains("MinIO")
                || message.contains("存储")
                || message.contains("上传文件到"));
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
