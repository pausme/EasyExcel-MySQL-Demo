package com.huang.demo.excel.api.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;

@Getter
@Setter
public class StudentPageQueryRequest {

    @Min(value = 1, message = "页码必须大于等于1")
    private Integer pageNo = 1;

    @Min(value = 1, message = "每页条数必须大于等于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer pageSize = 20;

    @Size(max = 32, message = "学号长度不能超过32")
    private String studentNo;

    @Size(max = 64, message = "姓名关键字长度不能超过64")
    private String nameKeyword;

    @Size(max = 64, message = "班级名称长度不能超过64")
    private String className;

    @Size(max = 16, message = "性别长度不能超过16")
    private String gender;

    @Min(value = 0, message = "最小年龄必须大于等于0")
    @Max(value = 150, message = "最小年龄不能超过150")
    private Integer minAge;

    @Min(value = 0, message = "最大年龄必须大于等于0")
    @Max(value = 150, message = "最大年龄不能超过150")
    private Integer maxAge;

    @Size(max = 32, message = "开始生日长度不能超过32")
    private String birthdayFrom;

    @Size(max = 32, message = "结束生日长度不能超过32")
    private String birthdayTo;

    @Min(value = 0, message = "导入版本必须大于等于0")
    private Long importVersion;
}
