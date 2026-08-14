# 项目后续优化 TODO

本文档用于沉淀 EasyExcel MySQL Demo 后续可继续演进的任务清单。当前项目已经具备异步导入、全量原子导入、异步导出、MinIO 文件交付、Redis + MySQL 任务中心和文件上传中心能力。后续优化重点从“能跑大数据量”转向“可运营、可恢复、可扩展、可观测”。

## 状态说明

| 状态 | 含义 |
| --- | --- |
| TODO | 尚未开始 |
| DOING | 正在开发 |
| DONE | 已完成并通过本地验证 |

## 任务总览

| 状态 | 优先级 | 任务 | 建议原因 |
| --- | --- | --- | --- |
| DONE | P0 | 导入错误明细文件 | 全量原子导入失败后，用户需要知道具体哪几行错了 |
| DONE | P0 | 导入文件持久化到 MinIO | 解决本地临时文件丢失后无法重试的问题 |
| DONE | P1 | 报表运行控制中心 | 向企业级调度报表规范靠拢，支持保存查询条件和历史运行 |
| TODO | P1 | 通用报表导出引擎 | 将学生导出抽象成可复用框架，支持多报表扩展 |
| TODO | P1 | 任务监控和指标 | 让系统具备生产排障能力 |
| TODO | P2 | 性能压测和调优报告 | 固化百万级导入导出的性能结论 |

## 已完成历史任务

| 状态 | 任务 | 关键内容 |
| --- | --- | --- |
| DONE | 项目初始化 | 基于 Spring Boot、EasyExcel、MyBatis、MySQL 搭建学生导入导出演示项目 |
| DONE | 配置安全改造 | 使用 `application.yml`，数据库密码等敏感信息改为环境变量 |
| DONE | 数据库建表脚本 | 提供 `create_database.sql`、`create_tables.sql`、`schema.sql` |
| DONE | MySQL + MyBatis 写库 | 使用 MyBatis XML 批量 `INSERT ... ON DUPLICATE KEY UPDATE` |
| DONE | 导入模板接口 | 提供学生 Excel 导入模板下载 |
| DONE | 百万级导出 | 游标分页、快照边界、异步任务、本地临时文件、MinIO 上传和签名下载 |
| DONE | Redis 任务状态 | 导出任务状态缓存 Redis，并持久化任务记录到 MySQL |
| DONE | 文件上传中心 | 普通上传、客户端直传、秒传、分片上传、分页查询和静态测试页 |
| DONE | 统一异步任务中心 | 抽象任务创建、运行中、成功、失败、取消、过期、重试和分页查询 |
| DONE | 真正异步导入 | 导入接口立即返回任务 ID，后台线程解析 Excel 并写库 |
| DONE | 全量原子导入 | 使用 `student_import_stage` 暂存表，校验通过后单事务合并正式表 |
| DONE | 导入错误明细文件 | 导入校验失败时生成错误 Excel，上传 MinIO，并提供签名下载入口 |
| DONE | 导入文件持久化到 MinIO | 提交导入任务时保存源 Excel 到 MinIO，后台执行和重试从对象存储读取 |
| DONE | 报表运行控制中心 | 保存学生报表查询条件，基于运行控制创建导出任务并查看历史运行 |

---

## 1. 导入错误明细文件

状态：DONE

### 目标

导入失败时，不只返回粗粒度错误信息，还要生成一份错误明细 Excel，用户可以下载后看到每一行失败原因，修正后重新上传。

### 背景

当前全量原子导入已经能保证正式表不被部分写入，但失败信息还比较粗，例如：

- 必填字段为空
- 文件内存在重复 `student_no`
- 数据长度超过数据库字段限制
- 邮箱格式不合法
- 年龄不是合理范围

这些错误如果只返回总数，用户很难定位具体数据。

### 需求范围

1. 新增错误明细模型
   - `rowNo`：Excel 数据行号
   - `studentNo`：学号
   - `name`：姓名
   - `errorMessage`：错误原因，多个错误用分号拼接
   - 原始字段：年龄、性别、班级、邮箱、生日

2. 校验规则
   - `studentNo` 必填，长度不超过 32
   - `name` 必填，长度不超过 64
   - `gender` 长度不超过 16
   - `className` 长度不超过 64
   - `email` 长度不超过 128，可选做邮箱格式校验
   - `birthday` 长度不超过 32，可选做日期格式校验
   - 同一文件内 `studentNo` 不允许重复

3. 错误文件生成
   - 使用 EasyExcel 生成 `student-import-error-{taskId}.xlsx`
   - 上传到 MinIO，例如 `excel/student/import-error/`
   - 任务失败时在 `resultPayload` 中保存错误文件 `objectKey`

4. 下载接口
   - `GET /api/excel/import/{taskId}` 返回错误文件信息
   - `GET /api/excel/import/{taskId}/error-file` 下载错误明细
   - 仅任务归属用户可下载
   - 返回 302 MinIO 签名地址

### 数据库建议

当前实现选择不新增错误表，直接扫描 `student_import_stage` 暂存表生成错误文件。后续如果需要长期保留错误明细，可新增：

```sql
CREATE TABLE student_import_error (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    import_task_id VARCHAR(64) NOT NULL,
    row_no INT NOT NULL,
    student_no VARCHAR(32),
    error_message VARCHAR(1024) NOT NULL,
    created_at DATETIME NOT NULL,
    KEY idx_student_import_error_task_row (import_task_id, row_no)
);
```

### 验收标准

- 上传包含空 `student_no` 的 Excel，任务失败，正式表不变。
- 任务详情能看到错误文件信息。
- 下载错误文件后，能看到具体错误行和错误原因。
- 上传包含重复 `student_no` 的 Excel，所有重复行都能在错误文件中体现。
- 错误文件不长期公开访问，下载必须走签名 URL。

### 完成记录

- 新增 `StudentImportErrorRow` 错误明细 Excel 行模型。
- 新增 `StudentImportValidationException` 携带错误行。
- 暂存表字段扩容，避免超长数据在暂存阶段提前失败。
- Mapper 支持查询必填、长度、年龄、邮箱格式和重复学号错误行。
- 导入失败时生成错误 Excel，并上传到 MinIO。
- 导入任务结果保存 `errorCount`、`errorFileName`、`errorObjectKey`。
- 新增导入状态查询和错误文件下载接口。

---

## 2. 导入文件持久化到 MinIO

状态：DONE

### 目标

导入任务提交后，将原始 Excel 文件保存到 MinIO，任务重试时从 MinIO 读取文件，避免应用重启、本地临时目录清理或多实例部署导致任务不可重试。

### 背景

当前异步导入会先把上传文件保存到本地临时目录，再由后台线程读取。单机开发没问题，但生产环境存在几个风险：

- 应用重启后临时文件可能丢失
- 多实例部署时任务在 A 节点提交，重试落到 B 节点找不到文件
- 本地磁盘空间不可控
- 任务重试强依赖本地文件仍然存在

### 需求范围

1. 文件保存
   - 导入接口收到文件后上传到 MinIO
   - 对象路径建议：`excel/student/import-source/{yyyyMMdd}/{importId}.xlsx`
   - 任务 payload 保存 `sourceObjectKey`、`originalName`、`fileSize`

2. 后台读取
   - `StudentImportTaskServiceImpl` 后台执行时从 MinIO 获取输入流
   - 本地临时文件只作为可选缓存，不作为任务恢复的唯一来源

3. 重试行为
   - 失败、取消、过期任务允许重试时，从 MinIO 原文件重新解析
   - 如果源文件对象不存在，任务失败并提示“导入源文件不存在或已过期”

4. 生命周期
   - MinIO 为 `excel/student/import-source/` 配置生命周期
   - 默认保留 1 到 7 天，可通过配置控制

### 配置建议

```yaml
app:
  minio:
    import-source-object-prefix: ${MINIO_IMPORT_SOURCE_OBJECT_PREFIX:excel/student/import-source}
    import-source-retention-days: ${MINIO_IMPORT_SOURCE_RETENTION_DAYS:1}
```

### 验收标准

- 提交导入任务后，MinIO 能看到原始导入文件。
- 删除本地临时目录后，重试任务仍能执行。
- 应用重启后，失败任务仍能重试。
- 源文件过期被删除后，重试任务明确失败并记录原因。

### 完成记录

- 新增导入源文件 `sourceObjectKey` 和 `fileSize` 任务 payload 字段，保留 `temporaryFilePath` 兼容历史任务。
- 提交导入任务时将源 Excel 上传到 MinIO `excel/student/import-source/{yyyyMMdd}/student-import-{businessKey}.xlsx`。
- 后台导入和任务重试统一从 MinIO 打开源文件输入流。
- 如果任务创建失败，会清理已经上传的源对象。
- 如果源对象不存在或生命周期过期，任务失败并提示“导入源文件不存在或已过期”。
- 为导入源文件前缀配置 MinIO 生命周期，默认 1 天后清理。

---

## 3. 报表运行控制中心

状态：DONE

### 目标

参考调度类异步导出报表规范，增加“运行控制”能力：用户可以保存报表查询条件，点击运行后生成导出任务，并查看历史运行记录。

### 背景

当前学生导出是直接导出全量学生表，没有复杂条件，也没有运行参数保存。企业级报表通常需要：

- 保存用户自己的查询条件
- 同一个用户维护多个运行控制
- 点击运行后异步生成文件
- 查看本人历史运行结果

### 接口设计

基础路径建议：`/api/report/student-runs`

| 接口名称 | 请求方式 | 路径 | 说明 |
| --- | --- | --- | --- |
| 分页查询运行控制 | POST | `/page` | 查询当前用户创建的运行控制 |
| 创建运行控制 | POST | `/create` | 保存一组查询条件 |
| 查询运行控制详情 | GET | `/{runId}` | 查看运行条件 |
| 修改运行控制 | POST | `/{runId}/update` | 修改查询条件 |
| 删除运行控制 | POST | `/{runId}/delete` | 逻辑删除运行控制 |
| 运行报表 | POST | `/{runId}/run` | 基于运行控制创建导出任务 |
| 查询运行历史 | POST | `/{runId}/tasks` | 查询该运行控制下的历史导出任务 |

### 表设计建议

```sql
CREATE TABLE student_report_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    run_control_code VARCHAR(64) NOT NULL,
    run_name VARCHAR(128) NOT NULL,
    student_no VARCHAR(32),
    name_keyword VARCHAR(64),
    class_name VARCHAR(64),
    gender VARCHAR(16),
    min_age INT,
    max_age INT,
    status VARCHAR(32) NOT NULL,
    deleted BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_student_report_run_run_id (run_id),
    UNIQUE KEY uk_student_report_run_owner_code (owner_id, run_control_code, deleted),
    KEY idx_student_report_run_owner_created_at (owner_id, created_at),
    KEY idx_student_report_run_owner_status_created_at (owner_id, status, created_at)
);
```

### 业务规则

- `owner_id + run_control_code` 唯一。
- 用户只能查询、修改、运行自己的运行控制。
- 删除采用逻辑删除。
- 运行控制只保存查询条件，不直接保存导出文件。
- 每次点击运行都创建新的 `EXPORT` 任务。

### 验收标准

- 用户 A 看不到用户 B 的运行控制。
- 同一用户重复创建相同 `runControlCode` 会失败。
- 修改运行控制后，再运行报表使用最新条件。
- 运行报表后，可以在任务中心查询对应任务。

### 完成记录

- 新增 `student_report_run` 表和 MyBatis Mapper，支持运行控制创建、修改、逻辑删除、分页和详情。
- 新增 `/api/report/student-runs` 接口组，覆盖创建、分页、详情、修改、删除、运行和历史任务查询。
- 运行控制支持 `studentNo`、`nameKeyword`、`className`、`gender`、`minAge`、`maxAge` 查询条件。
- 学生导出任务 payload 支持携带查询条件，导出仍使用 `id > lastId AND id <= snapshotMaxId` 游标分页。
- 基于运行控制创建导出任务时，使用 `runId` 作为任务 `businessKey`，可按运行控制查询历史导出任务。
- 删除后的运行控制 `deleted` 写入本行 id，允许同一用户重新创建相同 `runControlCode`。

---

## 4. 通用报表导出引擎

### 目标

把当前学生导出中的任务提交、分页查询、Excel 写入、MinIO 上传、任务状态更新抽象成通用报表引擎。后续新增一个报表时，只需要实现查询参数、Sheet 配置和分页查询逻辑。

### 背景

当前 `ExportTaskServiceImpl` 和学生业务强绑定：

- 文件名写死
- Sheet 写死
- Excel VO 写死
- 查询逻辑写死
- 只支持单 Sheet

如果后续继续加报表，会复制很多代码。

### 设计建议

1. 抽象基类

```java
public abstract class AbstractReportExportJob<R> {

    protected abstract R fetchRunParams(String runId, String ownerId);

    protected abstract List<SheetConfig> getSheetConfigs(R runParams);

    protected abstract List<?> querySheetData(R runParams, SheetConfig sheetConfig, ReportPageCursor cursor);

    protected abstract String getDirectory();

    protected String getReportFileName(R runParams) {
        return "report-" + System.currentTimeMillis() + ".xlsx";
    }
}
```

2. Sheet 配置

```java
public class SheetConfig {
    private int sheetIndex;
    private String sheetName;
    private Class<?> headClass;
    private List<ReportConditionField> conditionFields;
    private Map<Integer, Integer> columnWidths;
    private int conditionSeparatorRows;
}
```

3. 支持能力
   - 单 Sheet
   - 多 Sheet
   - 条件行
   - 自定义列宽
   - 多级表头
   - 空数据导出表头
   - 导出取消检查
   - 导出进度更新

### 验收标准

- 学生导出迁移为 `StudentReportExportJob`。
- 新增一个简单报表时，不需要复制任务状态、MinIO 上传、下载 URL 逻辑。
- 多 Sheet 报表可以正常生成。
- 条件行显示在表头上方。
- 任务取消后不继续上传最终文件。

---

## 5. 任务监控和指标

### 目标

为导入、导出、文件上传和任务中心增加基础可观测性，方便定位慢任务、失败任务、队列堆积和外部依赖异常。

### 需求范围

1. 线程池指标
   - 导入任务线程池 active count
   - 导入 worker 线程池 active count
   - 导出线程池 active count
   - queue size
   - completed task count
   - rejected task count

2. 任务指标
   - 导入任务成功数、失败数、取消数
   - 导出任务成功数、失败数、取消数
   - 平均耗时、最大耗时
   - 每分钟任务提交量

3. 数据库批次指标
   - 暂存批次耗时
   - 最终合并耗时
   - 导出分页查询耗时
   - 单页数据量

4. MinIO 指标
   - 上传耗时
   - 下载签名生成失败次数
   - 对象删除失败次数

### 技术建议

- 引入 Spring Boot Actuator。
- 如需更完整，可接 Micrometer + Prometheus。
- 先用日志埋点也可以，但字段要稳定，便于后续采集。

### 验收标准

- 可以看到当前导入/导出线程池活跃线程和队列长度。
- 任务失败时能通过 taskId 串起日志。
- 100 万导入能输出解析、暂存、合并三个阶段耗时。
- MinIO 上传失败和数据库写入失败能区分。

---

## 6. 性能压测和调优报告

### 目标

形成一份可复现的百万级导入导出压测报告，说明不同参数对吞吐量和稳定性的影响。

### 背景

项目已经经历过多轮导入导出性能测试，但结果分散在日志和 README 中。后续需要把压测方式、环境、参数和结论固化，方便复盘和面试讲解。

### 压测维度

1. 导入 worker 数
   - 1、2、4、6、8、16
   - 观察吞吐、CPU、数据库连接占用、锁等待

2. 导入批次大小
   - 500、1000、2000、5000、10000
   - 观察内存、SQL 长度、单批耗时

3. Hikari 连接池
   - 5、10、20、30
   - 观察 worker 数与连接池大小的关系

4. 全量原子导入前后对比
   - 旧方案：批次直接 upsert 正式表
   - 新方案：暂存表 + 最终单事务合并
   - 对比总耗时、失败恢复能力、正式表一致性

5. 导出分页大小
   - 1000、3000、5000、10000
   - 观察查询耗时、Excel 写入耗时、内存占用

### 输出文档建议

新增文档：`docs/performance-report.md`

内容包括：

- 测试机器配置
- MySQL/Redis/MinIO 部署方式
- JVM 参数
- 数据量
- 参数矩阵
- 结果表格
- 曲线图或简化统计
- 最终推荐配置
- 风险和边界

### 验收标准

- 至少覆盖 10 万、100 万两个数据量。
- 每组参数至少重复 3 次，避免单次波动误判。
- 给出推荐配置，例如 `IMPORT_WORKER_COUNT=6` 或 `16` 的适用条件。
- 明确说明全量原子导入带来的额外成本。

---

## 建议实施顺序

1. `P0` 导入错误明细文件
2. `P0` 导入文件持久化到 MinIO
3. `P1` 报表运行控制中心
4. `P1` 通用报表导出引擎
5. `P1` 任务监控和指标
6. `P2` 性能压测和调优报告

这个顺序的原因是：先补齐用户可用性和失败恢复，再做架构抽象，最后做运维和性能报告。这样每一步都能在当前能力上自然增长，不会一下子把项目改散。
