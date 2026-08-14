package com.huang.demo.excel.api.dto;

import lombok.Data;

@Data
public class StudentReportRunPageQueryRequest {

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String runName;

    private String status;
}
