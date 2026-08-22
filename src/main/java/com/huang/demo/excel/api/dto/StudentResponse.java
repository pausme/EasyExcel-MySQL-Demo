package com.huang.demo.excel.api.dto;

import com.huang.demo.excel.domain.model.StudentExportRecord;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class StudentResponse {

    private final Long id;

    private final String studentNo;

    private final String name;

    private final Integer age;

    private final String gender;

    private final String className;

    private final String email;

    private final String birthday;

    public static StudentResponse from(StudentExportRecord record) {
        return StudentResponse.builder()
                .id(record.getId())
                .studentNo(record.getStudentNo())
                .name(record.getName())
                .age(record.getAge())
                .gender(record.getGender())
                .className(record.getClassName())
                .email(record.getEmail())
                .birthday(record.getBirthday())
                .build();
    }
}
