package com.huang.demo.file.service;

import com.huang.demo.file.api.dto.FilePageQueryRequest;
import com.huang.demo.file.api.dto.FilePageResponse;
import com.huang.demo.file.api.dto.DirectUploadInitRequest;
import com.huang.demo.file.api.dto.DirectUploadInitResponse;
import com.huang.demo.file.api.dto.InstantUploadCheckRequest;
import com.huang.demo.file.api.dto.InstantUploadCheckResponse;
import com.huang.demo.file.api.dto.MultipartPartsResponse;
import com.huang.demo.file.api.dto.MultipartUploadInitRequest;
import com.huang.demo.file.api.dto.MultipartUploadInitResponse;
import com.huang.demo.file.domain.entity.FileRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;

public interface FileCenterService {

    FileRecord upload(MultipartFile file) throws IOException;

    InstantUploadCheckResponse instantCheck(InstantUploadCheckRequest request);

    DirectUploadInitResponse initDirectUpload(DirectUploadInitRequest request);

    FileRecord completeDirectUpload(String uploadId);

    MultipartUploadInitResponse initMultipartUpload(MultipartUploadInitRequest request);

    MultipartUploadInitResponse resumeMultipartUpload(String uploadId);

    MultipartPartsResponse listMultipartParts(String uploadId);

    FileRecord completeMultipartUpload(String uploadId);

    void abortMultipartUpload(String uploadId);

    Optional<FileRecord> findNormalFile(String fileId);

    Optional<String> createDownloadUrl(String fileId);

    void delete(String fileId);

    FilePageResponse page(FilePageQueryRequest request);
}
