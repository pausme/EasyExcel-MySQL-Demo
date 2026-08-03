package com.huang.demo.excel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentExportRecord {

    private Long id;

    private String studentNo;

    private String name;

    private Integer age;

    private String gender;

    private String className;

    private String email;

    private String birthday;
}
