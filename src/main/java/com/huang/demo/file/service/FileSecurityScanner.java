package com.huang.demo.file.service;

import java.io.InputStream;

public interface FileSecurityScanner {

    void validateMetadata(String originalName, String contentType);

    void scan(InputStream inputStream, String originalName, String contentType, long fileSize);
}
