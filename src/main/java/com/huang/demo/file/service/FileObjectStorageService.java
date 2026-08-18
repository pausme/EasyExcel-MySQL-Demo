package com.huang.demo.file.service;

import com.huang.demo.file.domain.model.StoredFile;
import com.huang.demo.file.domain.model.StoredObject;

import java.io.InputStream;
import java.util.List;

public interface FileObjectStorageService {

    String bucketName();

    StoredFile upload(InputStream inputStream, long fileSize, String objectKey, String contentType);

    String createUploadUrl(String objectKey);

    String createDownloadUrl(String objectKey, String fileName);

    StoredObject statObject(String objectKey);

    InputStream openObject(String objectKey);

    void composeObject(String objectKey, List<String> sourceObjectKeys, String contentType);

    List<String> listObjectKeys(String objectPrefix);

    void deleteQuietly(String objectKey);

    void deleteQuietly(List<String> objectKeys);
}
