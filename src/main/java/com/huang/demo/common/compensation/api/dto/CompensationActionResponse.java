package com.huang.demo.common.compensation.api.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CompensationActionResponse {

    private final String compensationId;

    private final String status;

    private final boolean updated;
}
