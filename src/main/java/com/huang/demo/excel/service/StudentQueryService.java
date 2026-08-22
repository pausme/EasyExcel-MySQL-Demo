package com.huang.demo.excel.service;

import com.huang.demo.common.api.dto.CursorPageResponse;
import com.huang.demo.excel.api.dto.StudentCursorQueryRequest;
import com.huang.demo.excel.api.dto.StudentPageQueryRequest;
import com.huang.demo.excel.api.dto.StudentPageResponse;
import com.huang.demo.excel.api.dto.StudentResponse;

public interface StudentQueryService {

    StudentPageResponse page(StudentPageQueryRequest request);

    CursorPageResponse<StudentResponse> cursorPage(StudentCursorQueryRequest request);
}
