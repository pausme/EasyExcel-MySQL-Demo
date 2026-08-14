package com.huang.demo.excel.service.impl;

import com.huang.demo.excel.api.dto.StudentReportRunCreateRequest;
import com.huang.demo.excel.api.dto.StudentReportRunPageQueryRequest;
import com.huang.demo.excel.api.dto.StudentReportRunPageResponse;
import com.huang.demo.excel.api.dto.StudentReportRunResponse;
import com.huang.demo.excel.api.dto.StudentReportRunUpdateRequest;
import com.huang.demo.excel.config.ExcelDemoProperties;
import com.huang.demo.excel.domain.entity.StudentReportRun;
import com.huang.demo.excel.domain.model.ExportTask;
import com.huang.demo.excel.domain.model.StudentExportQuery;
import com.huang.demo.excel.domain.model.StudentReportRunStatus;
import com.huang.demo.excel.repository.StudentReportRunMapper;
import com.huang.demo.excel.service.ExportTaskService;
import com.huang.demo.excel.service.StudentReportRunService;
import com.huang.demo.task.api.dto.AsyncTaskPageQueryRequest;
import com.huang.demo.task.api.dto.AsyncTaskPageResponse;
import com.huang.demo.task.domain.model.AsyncTaskType;
import com.huang.demo.task.service.TaskCenterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class StudentReportRunServiceImpl implements StudentReportRunService {

    private static final Logger log = LoggerFactory.getLogger(StudentReportRunServiceImpl.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final StudentReportRunMapper runMapper;
    private final ExportTaskService exportTaskService;
    private final TaskCenterService taskCenterService;
    private final ExcelDemoProperties properties;

    public StudentReportRunServiceImpl(StudentReportRunMapper runMapper,
                                       ExportTaskService exportTaskService,
                                       TaskCenterService taskCenterService,
                                       ExcelDemoProperties properties) {
        this.runMapper = runMapper;
        this.exportTaskService = exportTaskService;
        this.taskCenterService = taskCenterService;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isInitEnabled()) {
            log.info("student report run database initialization skipped");
            return;
        }
        runMapper.createTableIfAbsent();
        log.info("student report run initialized");
    }

    @Override
    public StudentReportRunPageResponse page(String ownerId, StudentReportRunPageQueryRequest request) {
        StudentReportRunPageQueryRequest safeRequest =
                request == null ? new StudentReportRunPageQueryRequest() : request;
        String normalizedOwnerId = normalizeOwnerId(ownerId);
        int pageNo = normalizePageNo(safeRequest.getPageNo());
        int pageSize = normalizePageSize(safeRequest.getPageSize());
        String runName = normalizeOptionalText(safeRequest.getRunName(), 128);
        String status = normalizeOptionalStatus(safeRequest.getStatus());
        int offset = (pageNo - 1) * pageSize;

        long total = runMapper.countByOwner(normalizedOwnerId, runName, status);
        List<StudentReportRun> records = runMapper.listByOwnerPage(
                normalizedOwnerId, runName, status, offset, pageSize);
        List<StudentReportRunResponse> responses = new ArrayList<StudentReportRunResponse>(records.size());
        for (StudentReportRun record : records) {
            responses.add(StudentReportRunResponse.from(record));
        }
        return StudentReportRunPageResponse.builder()
                .total(total)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .records(responses)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StudentReportRunResponse create(String ownerId, StudentReportRunCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("运行控制参数不能为空");
        }
        String normalizedOwnerId = normalizeOwnerId(ownerId);
        String runControlCode = normalizeRequiredText(request.getRunControlCode(), "运行控制编码", 64);
        assertRunControlCodeAvailable(normalizedOwnerId, runControlCode, null);

        LocalDateTime now = LocalDateTime.now();
        StudentReportRun run = StudentReportRun.builder()
                .runId(UUID.randomUUID().toString().replace("-", ""))
                .ownerId(normalizedOwnerId)
                .runControlCode(runControlCode)
                .runName(normalizeRequiredText(request.getRunName(), "运行控制名称", 128))
                .studentNo(normalizeOptionalText(request.getStudentNo(), 32))
                .nameKeyword(normalizeOptionalText(request.getNameKeyword(), 64))
                .className(normalizeOptionalText(request.getClassName(), 64))
                .gender(normalizeOptionalText(request.getGender(), 16))
                .minAge(normalizeAge(request.getMinAge(), "最小年龄"))
                .maxAge(normalizeAge(request.getMaxAge(), "最大年龄"))
                .status(StudentReportRunStatus.NORMAL.name())
                .deleted(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        validateAgeRange(run.getMinAge(), run.getMaxAge());
        runMapper.insert(run);
        return StudentReportRunResponse.from(run);
    }

    @Override
    public StudentReportRunResponse detail(String ownerId, String runId) {
        return StudentReportRunResponse.from(findMyNormalRun(ownerId, runId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StudentReportRunResponse update(String ownerId, String runId, StudentReportRunUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("运行控制参数不能为空");
        }
        StudentReportRun run = findMyNormalRun(ownerId, runId);
        String runControlCode = normalizeRequiredText(request.getRunControlCode(), "运行控制编码", 64);
        assertRunControlCodeAvailable(run.getOwnerId(), runControlCode, run.getRunId());

        run.setRunControlCode(runControlCode);
        run.setRunName(normalizeRequiredText(request.getRunName(), "运行控制名称", 128));
        run.setStudentNo(normalizeOptionalText(request.getStudentNo(), 32));
        run.setNameKeyword(normalizeOptionalText(request.getNameKeyword(), 64));
        run.setClassName(normalizeOptionalText(request.getClassName(), 64));
        run.setGender(normalizeOptionalText(request.getGender(), 16));
        run.setMinAge(normalizeAge(request.getMinAge(), "最小年龄"));
        run.setMaxAge(normalizeAge(request.getMaxAge(), "最大年龄"));
        validateAgeRange(run.getMinAge(), run.getMaxAge());
        run.setUpdatedAt(LocalDateTime.now());
        updateRequired(run);
        return StudentReportRunResponse.from(run);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(String ownerId, String runId) {
        StudentReportRun run = findMyNormalRun(ownerId, runId);
        run.setStatus(StudentReportRunStatus.DELETED.name());
        run.setDeleted(run.getId() == null ? System.currentTimeMillis() : run.getId());
        run.setUpdatedAt(LocalDateTime.now());
        updateRequired(run);
        return true;
    }

    @Override
    public ExportTask run(String ownerId, String runId) {
        StudentReportRun run = findMyNormalRun(ownerId, runId);
        return exportTaskService.submitExport(
                run.getOwnerId(),
                run.getRunId(),
                "学生报表导出-" + run.getRunName(),
                toExportQuery(run));
    }

    @Override
    public AsyncTaskPageResponse pageTasks(String ownerId, String runId, AsyncTaskPageQueryRequest request) {
        StudentReportRun run = findMyNormalRun(ownerId, runId);
        return taskCenterService.pageMyTasksByBusinessKey(
                run.getOwnerId(), AsyncTaskType.EXPORT.name(), run.getRunId(), request);
    }

    private StudentReportRun findMyNormalRun(String ownerId, String runId) {
        String normalizedOwnerId = normalizeOwnerId(ownerId);
        StudentReportRun run = runMapper.findByRunId(normalizeRequiredText(runId, "运行控制 ID", 64))
                .orElseThrow(() -> new IllegalArgumentException("运行控制不存在"));
        if (!normalizedOwnerId.equals(run.getOwnerId()) || run.getDeleted() == null || run.getDeleted() != 0L) {
            throw new IllegalArgumentException("运行控制不存在");
        }
        return run;
    }

    private void assertRunControlCodeAvailable(String ownerId, String runControlCode, String currentRunId) {
        Optional<StudentReportRun> existing = runMapper.findNormalByOwnerAndCode(ownerId, runControlCode);
        if (!existing.isPresent()) {
            return;
        }
        if (currentRunId != null && currentRunId.equals(existing.get().getRunId())) {
            return;
        }
        throw new IllegalArgumentException("运行控制编码已存在");
    }

    private void updateRequired(StudentReportRun run) {
        int updated = runMapper.update(run);
        if (updated == 0) {
            throw new IllegalStateException("运行控制更新失败，runId=" + run.getRunId());
        }
    }

    private StudentExportQuery toExportQuery(StudentReportRun run) {
        return StudentExportQuery.builder()
                .studentNo(run.getStudentNo())
                .nameKeyword(run.getNameKeyword())
                .className(run.getClassName())
                .gender(run.getGender())
                .minAge(run.getMinAge())
                .maxAge(run.getMaxAge())
                .build();
    }

    private String normalizeOwnerId(String ownerId) {
        if (ownerId == null || ownerId.trim().isEmpty()) {
            return "anonymous";
        }
        String normalized = ownerId.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private String normalizeRequiredText(String value, String fieldName, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private String normalizeOptionalText(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private Integer normalizeAge(Integer age, String fieldName) {
        if (age == null) {
            return null;
        }
        if (age < 0 || age > 150) {
            throw new IllegalArgumentException(fieldName + "必须在0到150之间");
        }
        return age;
    }

    private void validateAgeRange(Integer minAge, Integer maxAge) {
        if (minAge != null && maxAge != null && minAge > maxAge) {
            throw new IllegalArgumentException("最小年龄不能大于最大年龄");
        }
    }

    private String normalizeOptionalStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        StudentReportRunStatus.valueOf(normalized);
        return normalized;
    }

    private int normalizePageNo(Integer pageNo) {
        if (pageNo == null || pageNo < 1) {
            return 1;
        }
        return pageNo;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
