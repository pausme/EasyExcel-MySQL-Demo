package com.huang.demo.excel.domain.model;

import java.util.Locale;

public enum StudentImportMode {

    OVERWRITE,
    APPEND,
    VALIDATE_ONLY;

    public static StudentImportMode normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return OVERWRITE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (StudentImportMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("导入模式不支持，支持值：OVERWRITE、APPEND、VALIDATE_ONLY");
    }
}
