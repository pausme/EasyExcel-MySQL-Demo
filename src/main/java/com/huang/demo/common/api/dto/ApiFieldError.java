package com.huang.demo.common.api.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiFieldError {

    private final String field;

    private final String message;

    private final Object rejectedValue;
}
