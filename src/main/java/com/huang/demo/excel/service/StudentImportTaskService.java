package com.huang.demo.excel.service;

import com.huang.demo.task.domain.entity.AsyncTaskRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface StudentImportTaskService {

    AsyncTaskRecord submitImport(MultipartFile file, String ownerId) throws IOException;
}
