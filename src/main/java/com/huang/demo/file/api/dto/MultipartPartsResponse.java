package com.huang.demo.file.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class MultipartPartsResponse {

    private final String uploadId;

    private final Integer partCount;

    private final List<Integer> uploadedParts;
}
