package com.huang.demo.excel.service;

import com.huang.demo.excel.api.dto.ImportTaskResponse;
import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Optional;

public interface StudentImportTaskService {

    AsyncTaskRecord submitImport(MultipartFile file, String ownerId) throws IOException;

    Optional<ImportTaskResponse> findImportTask(String taskId, String ownerId);

    Optional<String> createErrorFileDownloadUrl(String taskId, String ownerId);
}
