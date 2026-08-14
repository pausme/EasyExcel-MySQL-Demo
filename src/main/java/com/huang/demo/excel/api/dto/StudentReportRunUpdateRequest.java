package com.huang.demo.excel.api.dto;

import lombok.Data;

@Data
public class StudentReportRunUpdateRequest {

    private String runControlCode;

    private String runName;

    private String studentNo;

    private String nameKeyword;

    private String className;

    private String gender;

    private Integer minAge;

    private Integer maxAge;
}
