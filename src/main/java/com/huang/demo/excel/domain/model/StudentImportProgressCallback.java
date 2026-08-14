package com.huang.demo.excel.domain.model;

public interface StudentImportProgressCallback {

    StudentImportProgressCallback NONE = new StudentImportProgressCallback() {
        @Override
        public void onParsed(int parsedCount, int parsedBatchCount) {
        }

        @Override
        public void onCommitted(int importedCount, int importedBatchCount) {
        }

        @Override
        public void checkCanceled() {
        }
    };

    void onParsed(int parsedCount, int parsedBatchCount);

    void onCommitted(int importedCount, int importedBatchCount);

    void checkCanceled();
}
