package com.huang.demo.excel.service;

import java.nio.file.Path;
import java.io.InputStream;

public interface MinioObjectStorageService {

    void uploadExcel(Path filePath, String objectKey);

    void uploadExcel(InputStream inputStream, long fileSize, String objectKey);

    void uploadFile(Path filePath, String objectKey, String contentType);

    void uploadFile(InputStream inputStream, long fileSize, String objectKey, String contentType);

    InputStream openObject(String objectKey);

    void ensureObjectExists(String objectKey);

    String createDownloadUrl(String objectKey, String fileName);

    void deleteQuietly(String objectKey);
}
