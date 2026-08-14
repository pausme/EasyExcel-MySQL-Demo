package com.huang.demo.excel.report;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ReportSheetConfig {

    private final int sheetIndex;

    private final String sheetName;

    private final Class<?> headClass;
}
