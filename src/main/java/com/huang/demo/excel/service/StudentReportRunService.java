package com.huang.demo.excel.service;

import com.huang.demo.excel.api.dto.StudentReportRunCreateRequest;
import com.huang.demo.excel.api.dto.StudentReportRunPageQueryRequest;
import com.huang.demo.excel.api.dto.StudentReportRunPageResponse;
import com.huang.demo.excel.api.dto.StudentReportRunResponse;
import com.huang.demo.excel.api.dto.StudentReportRunUpdateRequest;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.task.api.dto.AsyncTaskPageQueryRequest;
import com.huang.demo.task.api.dto.AsyncTaskPageResponse;

public interface StudentReportRunService {

    StudentReportRunPageResponse page(String ownerId, StudentReportRunPageQueryRequest request);

    StudentReportRunResponse create(String ownerId, StudentReportRunCreateRequest request);

    StudentReportRunResponse detail(String ownerId, String runId);

    StudentReportRunResponse update(String ownerId, String runId, StudentReportRunUpdateRequest request);

    boolean delete(String ownerId, String runId);

    ExportTask run(String ownerId, String runId);

    AsyncTaskPageResponse pageTasks(String ownerId, String runId, AsyncTaskPageQueryRequest request);
}
