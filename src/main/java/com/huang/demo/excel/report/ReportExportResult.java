package com.huang.demo.excel.report;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ReportExportResult {

    private final long total;

    private final long exported;

    private final int sheetCount;
}
