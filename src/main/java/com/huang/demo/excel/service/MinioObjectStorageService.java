package com.huang.demo.excel.service;

import java.nio.file.Path;

public interface MinioObjectStorageService {

    void uploadExcel(Path filePath, String objectKey);

    String createDownloadUrl(String objectKey, String fileName);

    void deleteQuietly(String objectKey);
}
