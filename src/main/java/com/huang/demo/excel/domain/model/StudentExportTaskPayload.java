package com.huang.demo.excel.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentExportTaskPayload {

    private Long snapshotMaxId;

    private String fileName;

    private StudentExportQuery query;
}
