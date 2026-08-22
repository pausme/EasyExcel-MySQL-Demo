package com.huang.demo.common.compensation.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CompensationPageResponse {

    private final long total;

    private final int pageNo;

    private final int pageSize;

    private final List<CompensationResponse> records;
}
