package com.huang.demo.excel.service;

import java.io.InputStream;
import java.nio.file.Path;

public interface MinioObjectStorageService {

    void uploadExcel(Path filePath, String objectKey);

    InputStream downloadExcel(String objectKey);

    String createDownloadUrl(String objectKey, String fileName);
}
