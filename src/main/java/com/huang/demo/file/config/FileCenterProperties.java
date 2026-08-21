package com.huang.demo.file.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.file")
@Getter
@Setter
public class FileCenterProperties {

    private boolean initEnabled = true;

    private String objectPrefix = "files/general";

    private String multipartObjectPrefix = "files/multipart";

    private int downloadUrlExpireMinutes = 30;

    private int uploadUrlExpireMinutes = 30;

    private String uploadOperationLockKeyPrefix = "file:upload:operation:";

    private int uploadOperationLockTtlSeconds = 1800;

    private long multipartPartSize = 8L * 1024L * 1024L;

    private int multipartMaxPartCount = 1000;

    private int maxPageSize = 100;

    private long maxFileSizeBytes = 0L;

    private long maxTotalStorageBytesPerOwner = 0L;

    private int maxActiveUploadTasksPerOwner = 20;

    private int maxDailyUploadCountPerOwner = 0;

    private boolean corsEnabled = true;

    private List<String> corsAllowedOriginPatterns = new ArrayList<String>(Arrays.asList(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "null"));

    private boolean securityScanEnabled = true;

    private List<String> allowedUploadExtensions = new ArrayList<String>(Arrays.asList(
            "txt", "csv", "json", "xml", "md", "log", "properties", "yaml", "yml",
            "pdf", "png", "jpg", "jpeg", "gif", "bmp",
            "zip", "docx", "xlsx", "pptx",
            "bin", "mp3", "mp4", "rar", "7z"));

    private List<String> allowedUploadMimeTypes = new ArrayList<String>(Arrays.asList(
            "text/plain",
            "text/csv",
            "application/json",
            "application/xml",
            "text/xml",
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/bmp",
            "application/zip",
            "application/x-zip-compressed",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/octet-stream",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "audio/mpeg",
            "video/mp4"));
}
