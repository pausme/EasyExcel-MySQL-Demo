package com.huang.demo.excel.domain.model;

public enum StudentExportFormat {

    XLSX_SINGLE_SHEET("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv; charset=UTF-8"),
    ZIP_CSV_PARTS("zip", "application/zip");

    private final String fileExtension;
    private final String contentType;

    StudentExportFormat(String fileExtension, String contentType) {
        this.fileExtension = fileExtension;
        this.contentType = contentType;
    }

    public String getFileExtension() {
        return fileExtension;
    }

    public String getContentType() {
        return contentType;
    }

    public static StudentExportFormat parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return XLSX_SINGLE_SHEET;
        }
        String normalized = value.trim().toUpperCase();
        for (StudentExportFormat format : values()) {
            if (format.name().equals(normalized)) {
                return format;
            }
        }
        throw new IllegalArgumentException("不支持的导出格式，format=" + value);
    }
}
