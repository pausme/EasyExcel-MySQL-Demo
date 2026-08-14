package com.huang.demo.excel.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class StudentReportRunPageResponse {

    private final long total;

    private final int pageNo;

    private final int pageSize;

    private final List<StudentReportRunResponse> records;
}
