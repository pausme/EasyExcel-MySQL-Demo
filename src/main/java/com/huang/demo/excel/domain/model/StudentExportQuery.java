package com.huang.demo.excel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentExportQuery {

    private String studentNo;

    private String nameKeyword;

    private String className;

    private String gender;

    private Integer minAge;

    private Integer maxAge;
}
