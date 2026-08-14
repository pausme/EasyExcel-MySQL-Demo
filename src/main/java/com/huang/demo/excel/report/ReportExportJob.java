package com.huang.demo.excel.report;

import java.util.List;

public interface ReportExportJob<P> {

    String buildFileName(String businessKey, P params);

    Long resolveSnapshotMaxId(P params);

    List<ReportSheetConfig> getSheetConfigs(P params);

    long count(P params, ReportSheetConfig sheetConfig, Long snapshotMaxId);

    ReportPage queryPage(P params, ReportSheetConfig sheetConfig, ReportPageCursor cursor);
}
