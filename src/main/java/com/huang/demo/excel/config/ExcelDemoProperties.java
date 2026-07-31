package com.huang.demo.excel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.excel")
public class ExcelDemoProperties {

    private int exportPageSize = 500;

    private int sheetRowLimit = 5000;

    private int importBatchSize = 500;

    private int insertBatchSize = 2000;

    private int demoSeedCount = 12000;

    public int getExportPageSize() {
        return exportPageSize;
    }

    public void setExportPageSize(int exportPageSize) {
        this.exportPageSize = exportPageSize;
    }

    public int getSheetRowLimit() {
        return sheetRowLimit;
    }

    public void setSheetRowLimit(int sheetRowLimit) {
        this.sheetRowLimit = sheetRowLimit;
    }

    public int getImportBatchSize() {
        return importBatchSize;
    }

    public void setImportBatchSize(int importBatchSize) {
        this.importBatchSize = importBatchSize;
    }

    public int getInsertBatchSize() {
        return insertBatchSize;
    }

    public void setInsertBatchSize(int insertBatchSize) {
        this.insertBatchSize = insertBatchSize;
    }

    public int getDemoSeedCount() {
        return demoSeedCount;
    }

    public void setDemoSeedCount(int demoSeedCount) {
        this.demoSeedCount = demoSeedCount;
    }
}
