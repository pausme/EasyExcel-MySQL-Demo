package com.huang.demo.excel.api.dto;

import com.huang.demo.excel.domain.entity.StudentReportRun;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class StudentReportRunResponse {

    private final String runId;

    private final String ownerId;

    private final String runControlCode;

    private final String runName;

    private final String studentNo;

    private final String nameKeyword;

    private final String className;

    private final String gender;

    private final Integer minAge;

    private final Integer maxAge;

    private final String status;

    private final LocalDateTime createdAt;

    private final LocalDateTime updatedAt;

    public static StudentReportRunResponse from(StudentReportRun run) {
        return StudentReportRunResponse.builder()
                .runId(run.getRunId())
                .ownerId(run.getOwnerId())
                .runControlCode(run.getRunControlCode())
                .runName(run.getRunName())
                .studentNo(run.getStudentNo())
                .nameKeyword(run.getNameKeyword())
                .className(run.getClassName())
                .gender(run.getGender())
                .minAge(run.getMinAge())
                .maxAge(run.getMaxAge())
                .status(run.getStatus())
                .createdAt(run.getCreatedAt())
                .updatedAt(run.getUpdatedAt())
                .build();
    }
}
