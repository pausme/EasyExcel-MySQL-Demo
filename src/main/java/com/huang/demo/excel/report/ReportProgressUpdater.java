package com.huang.demo.excel.report;

public interface ReportProgressUpdater {

    void update(long completedCount, long totalCount, int progressPercent);
}
