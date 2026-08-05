package com.huang.demo.file.api.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InstantUploadCheckResponse {

    private final boolean exists;

    private final FileResponse file;
}
