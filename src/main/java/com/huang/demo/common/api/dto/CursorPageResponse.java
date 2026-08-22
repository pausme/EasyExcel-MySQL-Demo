package com.huang.demo.common.api.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class CursorPageResponse<T> {

    private final Long nextCursor;

    private final boolean hasMore;

    private final int pageSize;

    private final List<T> records;
}
