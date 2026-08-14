package com.huang.demo.excel.report;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ReportPageCursor {

    private final long lastCursor;

    private final Long maxCursor;

    private final int pageSize;
}
