package com.huang.demo.excel.report;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ReportPage {

    private final List<?> rows;

    private final long nextCursor;
}
