package com.huang.demo.common.api.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

@Getter
@Setter
public class CursorPageRequest {

    @Min(value = 0, message = "游标必须大于等于0")
    private Long cursor;

    @Min(value = 1, message = "每页条数必须大于等于1")
    @Max(value = 1000, message = "游标分页每页条数不能超过1000")
    private Integer pageSize = 20;
}
