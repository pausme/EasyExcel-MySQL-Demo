package com.huang.demo.excel.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentReportRun {

    private Long id;

    private String runId;

    private String ownerId;

    private String runControlCode;

    private String runName;

    private String studentNo;

    private String nameKeyword;

    private String className;

    private String gender;

    private Integer minAge;

    private Integer maxAge;

    private String status;

    private Long deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
