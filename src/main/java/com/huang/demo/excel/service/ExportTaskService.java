package com.huang.demo.excel.service;

import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.StudentExportQuery;

import java.util.Optional;

public interface ExportTaskService {

    ExportTask submitExport(String ownerId);

    ExportTask submitExport(String ownerId, String businessKey, String taskName, StudentExportQuery query);

    ExportTask submitExport(String ownerId, String format);

    Optional<ExportTask> findTask(String taskId);

    Optional<String> createDownloadUrl(ExportTask task);
}
