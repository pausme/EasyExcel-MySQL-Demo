# EasyExcel MySQL Demo

基于 Spring Boot、EasyExcel、MyBatis、MySQL、Redis 和 MinIO 的 Excel 导入导出演示项目。
重点演示大文件场景下的流式解析、批量写库、异步导入导出和对象存储。

## 功能概览

- Excel 导入：提交阶段校验 `.xlsx` 结构和容量边界，上传后创建 IMPORT 任务，后台流式读取 Excel，分批放入有界队列，由多个 worker 批量写入 MySQL 暂存表，校验通过后构建新数据版本并一次性发布可见版本。
- Excel 导出：异步提交任务，记录当前数据版本和最大 id，使用游标分页读取数据库，支持单 Sheet Excel、CSV 和 ZIP 分片 CSV，生成后上传 MinIO。
- 异步任务中心：统一记录任务状态、进度、失败原因、失败类型、可重试状态和重试次数，状态缓存 Redis，任务记录持久化 MySQL。
- 任务恢复和监控：任务执行写入 worker 心跳，应用定时恢复悬挂任务，并暴露 Actuator/Micrometer 指标。
- 统一 API 响应：常规 JSON 接口返回 `ApiResponse`，异常由全局处理器转换为稳定错误码；参数校验失败返回字段级 `fieldErrors`。
- 用户上下文：默认 demo 模式兼容本地调试，关闭 demo 后必须使用 Bearer token，任务、报表和文件按 owner 隔离。
- 报表运行控制：支持保存学生报表查询条件，基于运行控制创建导出任务，并查询运行历史。
- 通用报表导出引擎：抽象 Sheet 配置、快照计数、游标分页、Excel 写入、进度更新和取消检查。
- 文件下载：应用只返回 MinIO 签名地址，不经过应用服务器转发大文件内容。
- 通用文件中心：支持普通上传、元数据查询、逻辑删除、分页查询和签名下载。
- 查询接口：任务、文件、学生数据、下载审计和补偿记录均提供组合条件分页查询；学生数据额外提供游标分页。
- 补偿管理：管理员可分页查看补偿记录并手动重试或忽略；对象清理类补偿支持后台自动重试和退避。
- 可观测性：任务事件、TraceId、下载审计、线程池指标、导入导出行速率、MinIO 上传耗时和错误文件指标已接入 Micrometer。
- 文件中心测试页：启动应用后访问 `/file-upload-test.html`，可测试秒传、客户端直传和分片上传。
- 文件中心测试页还能展示分片状态、断点继续和失败分片重试，适合联调文件中心。
- 数据更新：同一可见版本内使用 `student_no` 作为唯一业务键，重复导入时按新版本整体发布。
- 压测工具：提供 Python 标准库脚本，支持并发矩阵和吞吐量统计。
- 回归样本：提供统一 fixture 生成脚本，样本说明见 [regression-datasets.md](docs/regression-datasets.md)。

## 当前验证结论

当前以内部标准测试环境的 R11 轮为当前权威结果。R7~R10 保留为历史过程记录，本机和历史云端测试只作为参考，不再作为容量结论。

| 项目 | 数据量 | 结果 |
| --- | ---: | --- |
| 单元/切片测试 | 38 类 / 164 用例 | 全部通过 |
| 接口扁平化测试 | 77 用例 | R11 标准环境 77/77 通过；F-02/F-09/F-12 已关闭，F-11 受控，新增文件安全扫描用例通过 |
| 异步导出 | 1,000,006 行 × 3 | 全部成功，平均约 23,317 行/s |
| 异步导入 | 100,000 行 × 3 | 全部成功，平均约 4,511 行/s |
| 异步导入 | 1,000,000 行 | 默认护栏拦截超 20 万行任务；放开护栏后在标准环境已实测成功，平均约 4,521 行/s |

重要边界：

- 导出默认使用单 Sheet XLSX 以保证“导出文件可直接回导”；超过 Excel 单 Sheet 数据行上限 `1,048,575` 时会失败并提示缩小范围或改用 CSV。CSV 和 ZIP 分片 CSV 适合归档、分析和审计，不承诺直接回导。
- 导入采用“暂存表 + 全量校验 + 新版本构建 + 可见版本切换”，可以在写正式表前拦截坏数据，并避免失败导入污染当前可见版本。
- 标准环境已经通过 swap 缓解小规格机器 OOM；默认还会用最大行数和文件大小限制拒绝超出当前环境容量的导入任务。

## 实现思路

### 统一错误响应

异常响应由 `GlobalExceptionHandler` 统一处理，核心结构如下：

```json
{
  "success": false,
  "code": "EXCEL_PARAM_ERROR",
  "message": "请求参数校验失败",
  "traceId": "trace-id",
  "bizId": null,
  "retryable": false,
  "suggestion": "请根据 fieldErrors 修正请求参数",
  "fieldErrors": [
    {
      "field": "pageSize",
      "message": "每页条数不能超过100",
      "rejectedValue": "500"
    }
  ],
  "timestamp": "2026-08-22T09:34:00"
}
```

错误码按模块收敛：`EXCEL_*`、`FILE_*`、`TASK_*`、`SECURITY_*`、`STORAGE_*` 和 `COMMON_*`。业务异常可额外返回 `bizId`、`retryable`、`suggestion`，异步任务失败详情也使用同一套语义。

### 导入流程

```text
HTTP 上传 Excel
        |
        v
校验 .xlsx 后缀、zip 文件头和 xlsx 必要结构
        |
        v
上传原始导入文件到 MinIO
        |
        v
任务中心创建 IMPORT 任务，接口立即返回 taskId
        |
        v
后台导入线程从 MinIO 读取源文件
        |
        v
EasyExcel 流式解析
        |
        v
每 `IMPORT_BATCH_SIZE` 行组成一个批次 -> 有界 BlockingQueue
        |
        v
多个导入 worker 消费批次并写入 student_import_stage
        |
        v
校验必填字段和文件内重复 student_no
        |
        v
按 `IMPORT_MERGE_CHUNK_SIZE` 从暂存表分块 upsert 到 student_record 的新 import_version
        |
        v
CAS 更新 student_import_version_control.current_version，发布新版本
```

导入任务的原始 Excel 会先保存到 MinIO 的 `excel/student/import-source/` 前缀，任务 payload 记录 `sourceObjectKey`、原始文件名和文件大小。
后台执行和任务重试都从 MinIO 读取源文件，不依赖本机临时目录；如果源文件生命周期过期或被删除，重试会明确失败并提示源文件不存在或已过期。导入校验失败时，任务详情会返回错误摘要和前 100 行预览，也可以通过 `/api/excel/import/{taskId}/errors?limit=20` 单独查询预览。
导入不会把整份 Excel 加载到内存中。解析线程只保留当前批次，队列容量有限，队列满时解析会产生背压。
解析后的批次先写入 `student_import_stage` 暂存表，全部解析和暂存成功后，先统一校验必填、长度、格式和文件内重复 `student_no`，再按 `IMPORT_MERGE_CHUNK_SIZE` 分块写入 `student_record` 的新 `import_version`。
查询和导出只读取 `student_import_version_control.current_version` 指向的数据版本；新版本只有最后 CAS 发布成功后才可见。Excel 后半段解析失败、暂存失败、最终校验失败、构建新版本失败或发布失败时，旧版本继续对外服务，未发布版本会按 `import_task_id` 清理。
如果导入校验失败，系统会生成错误明细 Excel 上传到 MinIO，用户可以通过导入任务状态接口查看错误文件信息，并通过签名地址下载。
导入任务进度会写入统一任务中心：`totalCount` 表示已经解析的行数，`completedCount` 表示已经暂存的行数，任务完成前进度最高到 95%，成功后为 100%。

导入 worker 的数量、导入任务并发数和数据库连接池相互约束：

```text
IMPORT_WORKER_COUNT * IMPORT_MAX_CONCURRENT_TASKS <= HIKARI_MAXIMUM_POOL_SIZE
```

默认只允许一个导入任务执行。导入失败时会清空待处理队列、取消 worker，并等待已经启动的 worker 收尾，最后清理本次导入的暂存数据。
数据库瞬时异常会按配置进行有限重试。
取消导入是尽力而为：解析线程和 worker 会在批次边界检查任务状态；如果取消发生在可见版本发布前，旧版本继续对外服务；如果取消发生在版本发布成功之后，新版本已经对外生效。

### 导出流程

```text
提交导出请求或运行报表控制
        |
        v
任务中心创建 EXPORT 任务，MySQL 持久化记录，Redis 缓存状态
        |
        v
记录当前 import_version 和 MAX(id)，按运行条件和 id 游标分页读取
        |
        v
通用报表导出引擎调用具体 Job 查询并写入 Excel
        |
        v
上传 MinIO，删除本地临时文件
        |
        v
查询任务状态并返回 MinIO 签名下载地址
```

导出使用 `import_version = snapshotVersion AND id > lastId AND id <= maxId` 的游标条件，避免大 offset 分页越来越慢，且保证一次导出固定读取同一个已发布版本。
学生报表运行控制会保存 `studentNo`、`nameKeyword`、`className`、`gender`、`minAge`、`maxAge` 等查询条件；点击运行时，运行控制的 `runId` 会作为导出任务 `businessKey`，因此可以按运行控制查看历史导出任务。
`ReportExportEngine` 负责通用写文件流程，`StudentReportExportJob` 只负责学生报表的文件名、Sheet 配置、快照边界、分页查询和 Excel 行转换。
导入和导出任务都已经接入统一任务中心，进度、失败原因、失败类型、可重试状态、取消和重试都会同步到 `async_task_record`，并缓存到 Redis。
为了让导出的文件可以直接作为导入文件使用，当前导出只生成一个 Sheet；超过 Excel 单 Sheet 行数限制时任务失败。

### 查询和管理接口

| 功能 | 方法与路径 | 说明 |
| --- | --- | --- |
| 用户登录 | `POST /api/auth/login` | 用户名密码登录，返回 Bearer access token 和 refresh token |
| 刷新令牌 | `POST /api/auth/refresh` | 使用 refresh token 换取新的 access token 和 refresh token |
| 学生分页查询 | `POST /api/students/page` | 支持学号、姓名、班级、性别、年龄范围、生日范围、导入版本过滤 |
| 学生游标查询 | `POST /api/students/cursor-page` | 使用 `cursor` + `pageSize` 翻页，适合大结果集连续浏览 |
| 下载审计查询 | `POST /api/download-audits/page` | 普通用户只查自己，管理员可按 ownerId 查询 |
| 补偿记录查询 | `POST /api/admin/compensations/page` | 管理员查看补偿原因、状态、重试次数和最近错误 |
| 补偿重试 | `POST /api/admin/compensations/{compensationId}/retry` | 将补偿记录切回 `PENDING`，等待调度重放 |
| 补偿忽略 | `POST /api/admin/compensations/{compensationId}/ignore` | 将补偿记录标记为 `IGNORED` |
| 运维首页聚合 | `GET /api/admin/ops/overview` | 管理员查看今日任务、失败任务、补偿积压、文件容量、线程池和最近异常 |
| 线程池监控 | `GET /api/tasks/metrics/thread-pools` | 管理员查看导入、导出线程池快照 |

分页查询统一限制 `pageSize <= 100`；学生游标分页限制 `pageSize <= 1000`。
关闭 demo 模式后，受保护接口必须携带 `Authorization: Bearer <accessToken>`。`API_SECURITY_INIT_ENABLED=true` 时会初始化 `security_user` 表；需要首次登录账号时，可临时开启 `API_SECURITY_BOOTSTRAP_ADMIN_ENABLED=true` 并配置启动管理员用户名和密码，启动完成后建议关闭 bootstrap 配置。

### 监控指标

Prometheus 可通过 `/actuator/prometheus` 抓取指标。核心业务指标包括：

| 指标 | 标签 | 含义 |
| --- | --- | --- |
| `demo.async.task.total` | `taskType`,`outcome` | 异步任务提交和状态流转次数 |
| `demo.async.task.duration` | `taskType`,`status` | 异步任务执行耗时 |
| `demo.thread.pool.rejected.total` | `pool` | 导入/导出线程池拒绝次数 |
| `demo.excel.rows.total` | `scene` | 导入/导出处理行数 |
| `demo.excel.row.rate` | `scene` | 导入/导出行处理速率采样 |
| `demo.storage.upload.duration` | `scene`,`success` | MinIO 上传耗时 |
| `demo.excel.error.file.total` | `outcome` | 导入错误明细文件生成结果 |
| `demo.compensation.backlog` | `status` | 补偿积压采样 |
| `demo.compensation.auto.execution.total` | `outcome`,`bizType`,`failureType` | 自动补偿执行结果次数 |

## 项目结构

```text
src/main/java/com/huang/demo
├── DemoApplication.java
├── common           # 统一响应、异常和请求 traceId
├── excel
    ├── config        # 配置属性和导入导出线程池
    ├── controller    # Excel HTTP 接口
    ├── listener      # EasyExcel 流式导入监听器
    ├── model         # Excel 行模型
    ├── report        # 通用报表导出引擎
    ├── domain/model  # 领域模型和任务结果
    ├── repository    # MyBatis Mapper
    └── service       # 导入导出业务编排
├── file              # 通用文件上传中心
├── security          # 轻量用户上下文和 API 访问拦截
└── task              # 统一异步任务中心

src/main/resources
├── db/migration      # Flyway 版本化迁移脚本
├── mapper            # MyBatis XML
└── db/mysql          # 数据库初始化脚本

scripts/import_load_test.py       # 导入压测脚本
docs/excel-import-export-test.md  # 功能测试文档
docs/import-load-test.md          # 压测使用说明
docs/project-roadmap-todo.md      # 后续优化 TODO
```

## 环境变量

数据库、Redis、Hikari 和 MinIO 的连接信息必须显式配置，不在 `application.yml` 中写入服务器地址或数据库凭据。

```bash
# 应用
export SERVER_PORT='<应用端口>'

# MySQL
export MYSQL_URL='<数据库地址>:<数据库端口>'
export MYSQL_USERNAME='your_mysql_username'
export MYSQL_PASSWORD='your_mysql_password'
export HIKARI_MAXIMUM_POOL_SIZE='10'

# Redis
export REDIS_HOST='<Redis地址>'
export REDIS_PORT='<Redis端口>'
export REDIS_DATABASE='your_redis_database'
export REDIS_PASSWORD='your_redis_password'

# 异步任务中心
export TASK_CENTER_INIT_ENABLED='true'
export TASK_CENTER_REDIS_KEY_PREFIX='task:center:'
export TASK_CENTER_CACHE_RETENTION_HOURS='24'
export TASK_CENTER_MAX_PAGE_SIZE='100'
export TASK_CENTER_DEFAULT_OWNER_ID='anonymous'
export TASK_CENTER_MAX_RETRY_COUNT='3'
export TASK_CENTER_MAX_ACTIVE_TASKS_PER_OWNER='10'
export TASK_CENTER_MAX_ACTIVE_TASKS_TOTAL='50'
export TASK_RECOVERY_ENABLED='true'
export TASK_WORKER_ID=''
export TASK_RECOVERY_HEARTBEAT_TIMEOUT_SECONDS='120'
export TASK_RECOVERY_BATCH_SIZE='20'

# API 权限
export API_SECURITY_INIT_ENABLED='true'
export API_SECURITY_DEMO_MODE='true'
export API_SECURITY_DEMO_USER_TOKEN='<配置的普通用户 Token>'
export API_SECURITY_DEMO_ADMIN_TOKEN='<配置的管理员 Token>'
export API_SECURITY_JWT_SECRET='<至少 32 位随机字符串>'
export API_SECURITY_ACCESS_TOKEN_EXPIRE_MINUTES='60'
export API_SECURITY_REFRESH_TOKEN_EXPIRE_MINUTES='10080'
export API_SECURITY_BOOTSTRAP_ADMIN_ENABLED='false'
export API_SECURITY_BOOTSTRAP_ADMIN_USER_ID='admin'
export API_SECURITY_BOOTSTRAP_ADMIN_USERNAME=''
export API_SECURITY_BOOTSTRAP_ADMIN_PASSWORD=''

# Flyway，默认关闭；开启后建议关闭各模块 INIT_ENABLED
export FLYWAY_ENABLED='false'
export FLYWAY_BASELINE_ON_MIGRATE='true'

# 自动补偿
export COMPENSATION_AUTO_EXECUTE_ENABLED='true'
export COMPENSATION_AUTO_EXECUTE_INITIAL_DELAY_MILLIS='60000'
export COMPENSATION_AUTO_EXECUTE_FIXED_DELAY_MILLIS='60000'
export COMPENSATION_AUTO_EXECUTE_BATCH_SIZE='20'
export COMPENSATION_AUTO_EXECUTE_LOCK_KEY='compensation:auto-execute:lock'
export COMPENSATION_AUTO_EXECUTE_LOCK_TTL_SECONDS='300'
export COMPENSATION_RETRY_BACKOFF_BASE_SECONDS='60'
export COMPENSATION_RETRY_BACKOFF_MAX_SECONDS='3600'

# MinIO
export MINIO_ENDPOINT='http://<MinIO地址>:<MinIO API端口>'
export MINIO_ACCESS_KEY='your_minio_access_key'
export MINIO_SECRET_KEY='your_minio_secret_key'
export MINIO_BUCKET_NAME='student-excel'
export MINIO_IMPORT_SOURCE_OBJECT_PREFIX='excel/student/import-source'
export MINIO_IMPORT_ERROR_OBJECT_PREFIX='excel/student/import-error'
export MINIO_IMPORT_SOURCE_RETENTION_DAYS='1'
export FILE_CENTER_INIT_ENABLED='true'
export FILE_CENTER_OBJECT_PREFIX='files/general'
export FILE_CENTER_MULTIPART_OBJECT_PREFIX='files/multipart'
export FILE_CENTER_DOWNLOAD_URL_EXPIRE_MINUTES='30'
export FILE_CENTER_UPLOAD_URL_EXPIRE_MINUTES='30'
export FILE_CENTER_MULTIPART_PART_SIZE='8388608'
export FILE_CENTER_MULTIPART_MAX_PART_COUNT='1000'
export FILE_CENTER_MAX_PAGE_SIZE='100'
export FILE_CENTER_MAX_FILE_SIZE_BYTES='0'
export FILE_CENTER_MAX_TOTAL_STORAGE_BYTES_PER_OWNER='0'
export FILE_CENTER_MAX_ACTIVE_UPLOAD_TASKS_PER_OWNER='20'
export FILE_CENTER_MAX_DAILY_UPLOAD_COUNT_PER_OWNER='0'
export FILE_CENTER_CORS_ENABLED='true'
export FILE_CENTER_CORS_ALLOWED_ORIGIN_PATTERNS='http://localhost:*,http://127.0.0.1:*,null'
export FILE_CENTER_SECURITY_SCAN_ENABLED='true'
export FILE_CENTER_ALLOWED_UPLOAD_EXTENSIONS='txt,csv,json,xml,md,log,properties,yaml,yml,pdf,png,jpg,jpeg,gif,bmp,zip,docx,xlsx,pptx,bin,mp3,mp4,rar,7z'
export FILE_CENTER_ALLOWED_UPLOAD_MIME_TYPES='text/plain,text/csv,application/json,application/xml,text/xml,application/pdf,image/png,image/jpeg,image/gif,image/bmp,application/zip,application/x-zip-compressed,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.openxmlformats-officedocument.presentationml.presentation,application/octet-stream,application/x-rar-compressed,application/x-7z-compressed,audio/mpeg,video/mp4'

# 数据清理
export DATA_CLEANUP_ENABLED='true'
export DATA_CLEANUP_INITIAL_DELAY_MILLIS='300000'
export DATA_CLEANUP_FIXED_DELAY_MILLIS='3600000'
export DATA_CLEANUP_BATCH_SIZE='200'
export DATA_CLEANUP_TASK_RETENTION_HOURS='168'
export DATA_CLEANUP_UPLOAD_TASK_RETENTION_HOURS='24'
export DATA_CLEANUP_DELETED_FILE_RETENTION_HOURS='24'
export DATA_CLEANUP_IMPORT_STAGE_RETENTION_HOURS='24'
export DATA_CLEANUP_IMPORT_VERSION_CLEANUP_ENABLED='true'
export DATA_CLEANUP_IMPORT_VERSION_RETAIN_COUNT='2'
export DATA_CLEANUP_DISTRIBUTED_LOCK_ENABLED='true'
export DATA_CLEANUP_LOCK_KEY='cleanup:retention:lock'
export DATA_CLEANUP_LOCK_TTL_SECONDS='1800'
```

建议将 MinIO Bucket 设置为私有。导出下载接口返回有效期默认 30 分钟的签名地址，避免大文件流量经过应用服务器。
导出对象默认写入 `excel/student/` 前缀，生命周期规则默认在 1 天后清理对象。
导入源文件默认写入 `excel/student/import-source/` 前缀，生命周期规则默认在 1 天后清理对象，用于失败任务重试和多实例部署下的任务恢复。
通用文件中心默认写入 `files/general/` 前缀，可通过 `FILE_CENTER_OBJECT_PREFIX` 调整。
数据清理任务默认每小时执行一次，按配置清理终态任务、已完成上传任务、过期未完成上传任务、逻辑删除文件记录、异常残留导入暂存数据以及过旧的导入历史版本。

## 数据库初始化

脚本位于 `src/main/resources/db/mysql`：

```text
create_database.sql  # 创建 demo 数据库
create_tables.sql    # 创建业务表和任务表
schema.sql            # 完整结构脚本
```

如果 SQL 客户端不能稳定执行多语句脚本，建议先执行 `create_database.sql`，再选择 `demo` 数据库执行 `create_tables.sql`。
`create_tables.sql` 会同时创建 `student_record`、`student_import_stage`、`student_report_run`、`file_record`、`file_upload_task`、`async_task_record` 和 `download_audit_record`。

项目也提供 Flyway 版本化迁移脚本，位于 `src/main/resources/db/migration`。生产环境建议设置：

```bash
export FLYWAY_ENABLED='true'
export TASK_CENTER_INIT_ENABLED='false'
export FILE_CENTER_INIT_ENABLED='false'
export EXCEL_INIT_ENABLED='false'
```

已有数据库接入时可以通过 `FLYWAY_BASELINE_ON_MIGRATE=true` 建立基线，后续表结构变更只新增迁移版本，不直接修改历史脚本。

## 生产部署

生产部署推荐继续使用“应用 jar + JRE 镜像 + 现有中间件 compose”的方式，不在仓库内放生产密钥，也不强制引入 Dockerfile。

部署相关模板：

| 文件 | 说明 |
| --- | --- |
| [deploy/easyexcel-demo.env.example](deploy/easyexcel-demo.env.example) | 生产环境变量模板，复制为服务器上的 `deploy/easyexcel-demo.env` 后替换占位符 |
| [deploy/docker-compose.easyexcel-demo.yml](deploy/docker-compose.easyexcel-demo.yml) | 应用服务 compose 覆盖文件，可与已有中间件 compose 叠加使用 |
| [docs/deployment-runbook.md](docs/deployment-runbook.md) | jar 构建、上传、启动、健康检查、日志排查和回滚手册 |

典型启动方式：

```bash
docker compose \
  -f docker-compose-software.yml \
  -f deploy/docker-compose.easyexcel-demo.yml \
  --env-file deploy/easyexcel-demo.env \
  up -d easyexcel-demo
```

同一 Docker 网络内建议使用服务名访问中间件：`MYSQL_URL=mysql:3306`、`REDIS_HOST=redis`、`MINIO_ENDPOINT=http://minio:9000`。浏览器下载签名地址使用 `MINIO_PUBLIC_ENDPOINT`。

## 启动和测试

使用 Maven：

```bash
mvn test
mvn spring-boot:run
```

Windows 或 macOS/Linux 在 Maven、JDK 已加入 PATH 后可以执行：

```powershell
mvn test
mvn spring-boot:run
```

### CI/CD

仓库提供两条 GitHub Actions 工作流：

- `.github/workflows/ci.yml`：push 或 PR 到 `main` 时自动执行 `git diff --check` 和 `mvn -B test`，并上传 surefire 测试报告。
- `.github/workflows/integration.yml`：支持手动触发和每周定时触发，使用 `docker-compose-test.yml` 启动 MySQL、Redis、MinIO，再执行 `scripts/run_integration_tests.sh` 跑真实依赖接口闭环。

集成测试失败时，可在 Actions 产物中下载 `target/integration-test/`、`target/surefire-reports/` 和 `docs/test/live-test-results.json` 排查。工作流只使用隔离测试账号和本地容器地址，不写入真实服务器地址、Token 或密钥。

大文件导入的 multipart 限制默认是 200MB：

```bash
export SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE='200MB'
export SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE='200MB'
```

## HTTP 接口

基础路径：`/api/excel`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/count` | 查询学生总数 |
| POST | `/seed/{count}` | 生成演示数据 |
| POST | `/export` | 提交异步导出任务 |
| GET | `/export/{taskId}` | 查询导出任务状态 |
| GET | `/export/{taskId}/download` | 获取 302 MinIO 签名下载地址 |
| GET | `/template` | 下载导入模板 |
| POST | `/import/precheck` | 预检导入文件，返回结构、容量和字段问题预览 |
| POST | `/import` | 上传 Excel 并提交异步导入任务，字段名为 `file` |
| GET | `/import/{taskId}` | 查询导入任务状态和错误文件信息 |
| GET | `/import/{taskId}/error-file` | 获取 302 MinIO 导入错误明细签名下载地址 |

基础路径：`/api/tasks`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/{taskId}` | 查询自己的异步任务详情 |
| POST | `/page` | 分页查询自己的异步任务 |
| POST | `/{taskId}/cancel` | 取消自己的异步任务 |
| POST | `/{taskId}/retry` | 重试自己的异步任务 |

常规 JSON 接口会统一包装为：

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "success",
  "data": {},
  "traceId": "请求追踪 ID",
  "timestamp": "2026-08-14T11:00:00"
}
```

demo 模式下仍兼容请求头 `X-User-Id`，不传时使用 `TASK_CENTER_DEFAULT_OWNER_ID`。关闭 demo 模式后，请使用 `Authorization: Bearer <配置的访问 Token>` 或按实际系统替换 `DemoTokenService`。文件、任务和报表运行控制都会按当前用户隔离。
异步任务中心默认限制同一用户同时活跃任务数和系统活跃任务总数，可通过 `TASK_CENTER_MAX_ACTIVE_TASKS_PER_OWNER` 和 `TASK_CENTER_MAX_ACTIVE_TASKS_TOTAL` 调整。
学生导入和导出任务都可以通过 `/api/tasks` 查询、取消和重试。`/api/excel/export/{taskId}` 仍保留导出专用状态接口，方便直接获取导出下载信息。
当导出文件已被 MinIO 生命周期清理或手动删除时，下载接口会返回 404，并把对应导出任务标记为 `EXPIRED`，避免任务状态长期停留在成功但文件不可用的假健康状态。
当导入错误文件已被 MinIO 生命周期清理或手动删除时，错误文件下载接口同样会返回 404，并把对应导入任务标记为 `EXPIRED`。
导入接口提交成功后立即返回任务 ID，任务完成后通过 `/api/tasks/{taskId}` 查看最终状态和结果；导出接口同样先返回任务 ID，完成后再调用状态接口和下载接口。
导出文件、导入错误文件和文件中心下载都会先做归属校验，再返回 MinIO 短期签名 URL，并向 `download_audit_record` 写入下载审计。审计只保存资源类型、资源 ID、对象 Key、文件名、请求 IP、User-Agent 和时间，不保存完整签名 URL。

`POST /api/tasks/page` 支持 `taskType`、`status`、`businessKey`、`failureType`、`keyword`、`createdFrom`、`createdTo` 筛选。任务详情会返回剩余重试次数、任务耗时、执行 worker、最近心跳和基于现有时间字段生成的生命周期摘要。

任务中心还提供：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/metrics/thread-pools` | 查询导入、导出线程池活跃线程、队列长度和完成任务数 |

Actuator 指标默认暴露 `health`、`info`、`metrics` 和 `prometheus`，可通过 `/actuator/metrics/demo.async.task.total`、`/actuator/metrics/demo.async.task.duration`、`/actuator/prometheus` 和 JVM executor 指标查看任务计数、耗时和线程池状态。Prometheus/Grafana 面板和告警规则见 [monitoring-alerting.md](docs/monitoring-alerting.md)。

基础路径：`/api/report/student-runs`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/page` | 分页查询自己的学生报表运行控制 |
| POST | `/create` | 创建学生报表运行控制 |
| GET | `/{runId}` | 查询运行控制详情 |
| POST | `/{runId}/update` | 修改运行控制查询条件 |
| POST | `/{runId}/delete` | 逻辑删除运行控制 |
| POST | `/{runId}/run` | 基于运行控制创建学生导出任务 |
| POST | `/{runId}/tasks` | 分页查询该运行控制的历史导出任务 |

运行控制的 `ownerId + runControlCode` 在未删除数据中唯一。删除后 `deleted` 会写入本行 id，因此同一用户可以重新创建相同编码。

## 文件上传中心

基础路径：`/api/files`

文件上传中心采用“文件内容放 MinIO、文件元数据放 MySQL”的设计：

```text
客户端
   |
   | 1. 请求后端生成上传任务或签名地址
   v
Spring Boot
   |
   | 2. 写入上传任务、校验文件、合并对象、写入文件记录
   v
MySQL                         MinIO
file_upload_task              文件内容、临时分片、签名 URL
file_record
```

普通上传时，文件字节会经过 Spring Boot；直传和分片上传时，文件字节由客户端直接发送到 MinIO，Spring Boot 只负责初始化、校验和落库。

### 数据表和对象说明

`file_record` 是正式文件记录。只有文件上传完成并通过校验后，才会写入该表，文件列表、详情、下载和秒传都只查询状态为 `NORMAL` 的记录。

`file_upload_task` 是直传或分片上传过程中的任务记录，保存 `uploadId`、预期文件大小、文件 MD5、MinIO 对象 Key、分片大小和分片数量。任务状态包括：

| 状态 | 含义 |
| --- | --- |
| `UPLOADING` | 已初始化，文件或分片还未完成 |
| `SUCCESS` | 已完成校验并生成 `file_record` |
| `ABORTED` | 已取消，临时分片会被清理 |

分片上传采用“分片对象 + `composeObject` 合并”的方式，而不是让 Spring Boot 接收每个分片。分片对象会写在 `files/multipart/{uploadId}/` 前缀下，合并成功或取消任务后清理。
如果页面刷新后需要继续上传，可以先用 `/multipart/{uploadId}/parts` 查询已上传分片，再用 `/multipart/{uploadId}/resume` 刷新签名地址后继续补传。

`download_audit_record` 是统一下载审计表。导出文件、导入错误明细和通用文件下载都会记录一条审计，用于排查“谁在什么时候请求过哪个资源的签名下载地址”。审计写入失败不会阻断下载主链路。

文件中心支持上传配额护栏：

| 配置 | 含义 |
| --- | --- |
| `FILE_CENTER_MAX_FILE_SIZE_BYTES` | 单文件大小上限，`0` 表示不限制 |
| `FILE_CENTER_MAX_TOTAL_STORAGE_BYTES_PER_OWNER` | 单用户正常文件 + 上传中任务预占用总空间上限，`0` 表示不限制 |
| `FILE_CENTER_MAX_ACTIVE_UPLOAD_TASKS_PER_OWNER` | 单用户同时处于 `UPLOADING` 的直传/分片任务数量上限 |
| `FILE_CENTER_MAX_DAILY_UPLOAD_COUNT_PER_OWNER` | 单用户当天成功上传文件数量上限，`0` 表示不限制 |

普通后端上传、直传初始化和分片初始化都会执行配额校验；秒传命中已有文件时不新增占用。过期未完成的直传/分片任务会由数据清理任务按 `DATA_CLEANUP_UPLOAD_TASK_RETENTION_HOURS` 清理，并删除临时对象或分片对象。

### 接口总览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/upload` | 文件经过后端上传，适合小文件或后端需要读取文件内容的场景 |
| `POST` | `/instant-check` | 根据文件 MD5 和文件大小检查是否可以秒传 |
| `POST` | `/direct/init` | 初始化整文件客户端直传，返回一个 MinIO PUT 签名地址 |
| `POST` | `/direct/{uploadId}/complete` | 校验直传对象并生成正式文件记录 |
| `POST` | `/multipart/init` | 初始化分片上传，返回每个分片的 MinIO PUT 签名地址 |
| `POST` | `/multipart/{uploadId}/resume` | 为已存在的分片任务刷新签名地址，继续断点续传 |
| `GET` | `/multipart/{uploadId}/parts` | 查询已经上传的分片，支持断点续传 |
| `POST` | `/multipart/{uploadId}/complete` | 校验所有分片、合并对象并生成正式文件记录 |
| `POST` | `/multipart/{uploadId}/abort` | 取消分片任务并清理临时分片 |
| `GET` | `/{fileId}` | 查询正式文件详情 |
| `GET` | `/{fileId}/download` | 返回 302，跳转到 MinIO 下载签名 URL |
| `POST` | `/{fileId}/delete` | 逻辑删除文件，并在事务提交后清理 MinIO 对象 |
| `POST` | `/page` | 分页查询正式文件元数据 |

### 1. 普通后端上传：`POST /api/files/upload`

请求格式是 `multipart/form-data`，字段名为 `file`。

处理逻辑：

1. Controller 校验文件不能为空。
2. Service 生成 `fileId` 和 MinIO 对象 Key。
3. 文件流经过 Spring Boot 写入 MinIO，同时计算文件 MD5。
4. 上传成功后，把文件名、大小、MD5、扩展名、桶名和对象 Key 写入 `file_record`。
5. 如果数据库写入失败，Service 会删除已经上传的 MinIO 对象，避免产生孤立文件。

该方式实现简单，但大文件会占用应用服务器的网络带宽和 Tomcat 请求线程，不建议作为百万级文件上传方案。

### 2. 秒传检查：`POST /api/files/instant-check`

请求示例：

```json
{
  "fileMd5": "文件内容的 32 位小写 MD5",
  "fileSize": 35180354
}
```

处理逻辑：

1. 校验 MD5 格式和文件大小。
2. 使用 `(file_md5, file_size)` 查询 `file_record`。
3. 只匹配状态为 `NORMAL` 的正式文件。
4. 如果存在，返回 `exists=true` 和已有文件信息，客户端无需再次上传。
5. 如果不存在，返回 `exists=false`，客户端继续选择直传或分片上传。

秒传依赖客户端计算真实文件 MD5。当前后端对直传和分片上传会校验对象大小，但不会重新读取整个 MinIO 对象再次计算 MD5，因此生产环境可以进一步增加对象 MD5 校验或可信上传回调。

### 3. 初始化客户端直传：`POST /api/files/direct/init`

请求示例：

```json
{
  "originalName": "demo.zip",
  "contentType": "application/zip",
  "fileSize": 35180354,
  "fileMd5": "文件内容的 32 位小写 MD5"
}
```

处理逻辑：

1. 校验原始文件名、文件大小和 MD5。
2. 再次执行秒传检查，避免客户端漏掉前置检查。
3. 如果文件已存在，直接返回 `instant=true`、`fileId` 和文件信息。
4. 如果文件不存在，生成 `uploadId`、`fileId` 和正式对象 Key。
5. 插入一条 `file_upload_task`，状态为 `UPLOADING`。
6. 使用 MinIO 生成整文件 PUT 签名地址。
7. 返回 `uploadId`、`fileId`、`uploadUrl` 和签名地址有效期。

此时文件内容还没有进入 MinIO，客户端拿到 `uploadUrl` 后，需要自己发起：

```text
PUT {uploadUrl}
请求体：完整文件二进制内容
```

### 4. 完成客户端直传：`POST /api/files/direct/{uploadId}/complete`

处理逻辑：

1. 根据 `uploadId` 查询 `file_upload_task`，并确认任务类型是 `DIRECT`。
2. 确认任务状态仍然是 `UPLOADING`。
3. 调用 MinIO `statObject` 检查正式对象是否存在。
4. 校验 MinIO 对象实际大小是否等于任务记录中的 `fileSize`。
5. 写入 `file_record`。
6. 将 `file_upload_task` 更新为 `SUCCESS`。
7. 返回正式文件信息。

如果对象不存在、大小不匹配或任务状态不正确，接口不会生成正式文件记录。

### 5. 初始化分片上传：`POST /api/files/multipart/init`

请求示例：

```json
{
  "originalName": "large-video.mp4",
  "contentType": "video/mp4",
  "fileSize": 104857600,
  "fileMd5": "文件内容的 32 位小写 MD5",
  "partSize": 8388608
}
```

处理逻辑：

1. 校验文件名、文件大小、MD5 和分片大小。
2. 分片大小最小为 5 MB，默认值由 `FILE_CENTER_MULTIPART_PART_SIZE` 控制。
3. 根据 `fileSize / partSize` 计算 `partCount`，超过 `FILE_CENTER_MULTIPART_MAX_PART_COUNT` 时拒绝。
4. 命中秒传时直接返回已有文件，不创建上传任务。
5. 未命中时生成 `uploadId`、`fileId` 和临时分片前缀。
6. 插入一条状态为 `UPLOADING` 的 `file_upload_task`。
7. 为每个分片生成一个独立的 MinIO PUT 签名地址。
8. 返回每个分片的 `partNumber`、`uploadUrl`、`objectKey` 和 `expectedSize`。

客户端根据 `partNumber` 切分文件，并发 PUT 到对应的 MinIO 地址。最后一个分片可以小于 `partSize`，其他分片必须等于 `partSize`。

### 6. 查询分片进度：`GET /api/files/multipart/{uploadId}/parts`

处理逻辑：

1. 根据 `uploadId` 查询分片上传任务。
2. 根据任务保存的临时前缀查询 MinIO 对象列表。
3. 从对象名解析已上传的分片序号。
4. 返回总分片数 `partCount` 和已上传序号 `uploadedParts`。

客户端可以在网络中断后调用该接口，只重新上传缺失的分片。当前实现的进度来源是 MinIO 对象列表，不依赖客户端内存中的进度。

### 6.1 恢复分片上传：`POST /api/files/multipart/{uploadId}/resume`

处理逻辑：

1. 根据 `uploadId` 查询分片上传任务，并确认状态仍然是 `UPLOADING`。
2. 复用任务保存的 `partSize`、`partCount`、`objectKey` 和 `partObjectPrefix`。
3. 为每个分片重新生成 MinIO PUT 签名地址。
4. 返回与初始化相同的分片信息，客户端再结合 `/parts` 查询结果继续补传。

这个接口主要用于页面刷新后恢复上传，或者分片签名已经过期时重新获取地址。

### 7. 完成分片上传：`POST /api/files/multipart/{uploadId}/complete`

处理逻辑：

1. 查询分片任务，并确认状态为 `UPLOADING`。
2. 按 `1..partCount` 顺序检查每个分片对象是否存在。
3. 校验每个分片的实际大小，防止缺片、错片或大小不一致。
4. 按分片顺序调用 MinIO `composeObject`，合并为正式对象。
5. 校验合并对象的总大小。
6. 写入 `file_record`，并将任务更新为 `SUCCESS`。
7. 事务提交后删除临时分片对象。

合并失败时会删除已经生成的正式对象；数据库事务失败时不会把任务标记为成功。客户端必须等该接口成功后，才能把文件当成正式文件使用。

### 8. 取消分片上传：`POST /api/files/multipart/{uploadId}/abort`

处理逻辑：

1. 查询分片任务并确认当前状态为 `UPLOADING`。
2. 将任务状态更新为 `ABORTED`。
3. 事务提交后删除该任务前缀下的所有临时分片。
4. 返回 `aborted=true` 和 `uploadId`。

取消后不能继续调用完成接口；如果客户端需要重新上传，应重新初始化任务。

### 9. 文件详情：`GET /api/files/{fileId}`

根据 `fileId` 查询状态为 `NORMAL` 的 `file_record`，返回文件名、大小、MD5、扩展名、内容类型和创建时间等元数据。逻辑删除后的文件不会被查询到。

### 10. 文件下载：`GET /api/files/{fileId}/download`

处理逻辑：

1. 查询正式文件记录。
2. 使用文件对象 Key 生成 MinIO GET 签名 URL。
3. Controller 返回 HTTP `302 Found`，通过 `Location` 跳转到 MinIO。
4. 文件内容不经过 Spring Boot，应用服务器不会代理大文件下载流量。

签名 URL 的有效期由 `FILE_CENTER_DOWNLOAD_URL_EXPIRE_MINUTES` 控制。因为接口返回的是 302，接口测试工具需要跟随重定向，浏览器会自动跳转。

### 11. 逻辑删除：`POST /api/files/{fileId}/delete`

处理逻辑：

1. 查询正式文件是否存在。
2. 将 `file_record.status` 更新为 `DELETED`。
3. 数据库事务提交后删除对应 MinIO 对象。
4. 返回 `deleted=true` 和 `fileId`。

删除接口不会物理删除数据库记录，因此可以保留审计信息；列表、详情和秒传检查都会忽略 `DELETED` 文件。

### 12. 文件分页：`POST /api/files/page`

请求示例：

```json
{
  "pageNo": 1,
  "pageSize": 20,
  "originalName": "demo",
  "fileExt": "zip"
}
```

处理逻辑：

1. `pageNo` 最小为 1，`pageSize` 会受到 `FILE_CENTER_MAX_PAGE_SIZE` 限制。
2. 按原始文件名和扩展名进行可选过滤。
3. 先查询总数，再查询当前页文件元数据。
4. 只返回状态为 `NORMAL` 的正式文件，不读取文件二进制内容。

### 完整上传流程

小文件可以直接使用后端上传：

```text
客户端 --multipart/form-data--> Spring Boot --文件流--> MinIO
                                             |
                                             v
                                      写入 file_record
```

客户端直传适合不希望文件流量经过应用服务器的场景：

```text
客户端 --初始化请求--> Spring Boot --写入--> file_upload_task
客户端 <--uploadUrl-- Spring Boot
客户端 --PUT 完整文件---------------------> MinIO
客户端 --complete 请求--> Spring Boot
Spring Boot --statObject 校验--> MinIO
Spring Boot --写入--> file_record
```

分片上传适合大文件、弱网络和断点续传：

```text
客户端 --multipart/init--> Spring Boot
客户端 <--多个分片 uploadUrl-- Spring Boot
客户端 --并发 PUT 分片-------------------> MinIO
客户端 --查询已上传分片------------------> Spring Boot
客户端 --multipart/complete-------------> Spring Boot
Spring Boot --校验分片并 composeObject--> MinIO
Spring Boot --写入正式记录--------------> file_record
```

### 浏览器测试页面

启动应用后访问：`http://localhost:${SERVER_PORT}/file-upload-test.html`。

测试页面会先计算文件 MD5，再调用秒传检查；未命中时可以选择直传或分片上传。分片上传默认每片 8 MB、并发数为 3，并支持查询分片和取消任务。

页面请求 Spring Boot 时，如果页面来自其他端口或直接从 `file://` 打开，需要确认 `FILE_CENTER_CORS_ALLOWED_ORIGIN_PATTERNS`；页面把文件 PUT 到 MinIO 时，还需要配置 MinIO CORS：

```yaml
MINIO_API_CORS_ALLOW_ORIGIN: "http://localhost:${SERVER_PORT}"
```

如果同一个 MinIO 还要给多个本地域名测试，可以用英文逗号分隔多个 Origin。

## 关键配置

导入配置通过 `app.excel` 绑定，常用环境变量如下：

```bash
export IMPORT_WORKER_COUNT='4'
export IMPORT_MAX_CONCURRENT_TASKS='1'
export IMPORT_QUEUE_CAPACITY='20'
export IMPORT_TRANSACTION_TIMEOUT_SECONDS='60'
export IMPORT_MAX_RETRY_TIMES='3'
export IMPORT_RETRY_BACKOFF_MILLIS='200'
export IMPORT_PROGRESS_LOG_INTERVAL='50'
export IMPORT_BATCH_SORT_ENABLED='true'
export IMPORT_BATCH_SIZE='2000'
export IMPORT_MAX_ROWS_PER_TASK='200000'
export IMPORT_MAX_FILE_SIZE_FOR_ASYNC='104857600'
export IMPORT_MERGE_CHUNK_SIZE='5000'
export IMPORT_AUTO_RECOVERY_ENABLED='false'
export IMPORT_TASK_CORE_POOL_SIZE='1'
export IMPORT_TASK_MAX_POOL_SIZE='1'
export IMPORT_TASK_QUEUE_CAPACITY='10'
export IMPORT_TEMP_DIR='/tmp/student-excel-import'
```

`IMPORT_AWAIT_TERMINATION_SECONDS` 只控制应用停机时等待线程池退出。
`IMPORT_TASK_*` 控制真正执行异步导入任务的外层线程池；每个导入任务内部还会按 `IMPORT_WORKER_COUNT` 启动写库 worker。
`IMPORT_WORKER_FINISH_WAIT_SECONDS` 控制导入任务等待 worker 收尾，默认 `0` 表示不主动超时。
如果设置为正数，应用启动时会校验它覆盖单批事务和重试时间窗口。
`IMPORT_MAX_ROWS_PER_TASK` 和 `IMPORT_MAX_FILE_SIZE_FOR_ASYNC` 是导入提交阶段的容量护栏，超过限制会直接返回 400，不会创建后台任务。
`IMPORT_MERGE_CHUNK_SIZE` 控制暂存表合并正式表的单块行数，默认 `5000`，代码会限制在 `1-20000`。
`IMPORT_AUTO_RECOVERY_ENABLED` 默认关闭，应用异常退出后的导入任务会标记失败，避免大文件在恢复协调器中自动反复重跑。

导出线程池可以通过以下变量调整：

```bash
export EXPORT_CORE_POOL_SIZE='2'
export EXPORT_MAX_POOL_SIZE='2'
export EXPORT_QUEUE_CAPACITY='10'
export EXPORT_AWAIT_TERMINATION_SECONDS='30'
export EXPORT_REJECTED_EXECUTION_POLICY='abort'
export EXPORT_PAGE_SIZE='5000'
```

## 一致性和容量边界

- 导入采用“暂存表 + 全量校验 + 分块构建新版本 + 可见版本切换”策略，正式版本发布前会完整校验本次导入数据。
- 暂存表写入和新版本构建都可以分批完成；只要解析、暂存、校验、构建新版本或发布版本失败，当前可见版本不变。
- 合并阶段使用多个短事务降低百万级长事务风险；这些短事务写入的是未发布版本，只有最后更新 `student_import_version_control.current_version` 成功后才对查询和导出可见。
- 同一个文件内出现重复 `student_no` 会直接失败，避免不同批次提交顺序影响最终结果。
- 导入源文件会持久化到 MinIO，任务执行和重试不依赖本机临时目录。
- 导入提交阶段会校验 `.xlsx` 后缀和文件真实结构，非 Excel 文件直接返回 400，不创建异步任务。
- 导入校验失败会生成错误明细文件，错误文件通过 MinIO 私有对象和签名 URL 下载。
- 当前导出只使用一个 Sheet，单 Sheet 数据行上限为 `1048575`。
- MinIO 对象由 Bucket 生命周期规则清理，Redis 任务状态过期不会自动删除已经上传的对象。
- 导入内存主要由当前批次、有限队列和并发任务数量决定；增加 worker 或并发任务会增加数据库连接和内存压力。

## 性能基线和压测

标准环境当前权威结果（R11，文件安全扫描版）：

| 场景 | 数据量 | 结果 | 吞吐 |
| --- | ---: | ---: | ---: |
| 服务端播种 | 1,000,000 行 | 88.5 s | 约 11,300 行/s |
| 异步导出 | 1,000,006 行，3 次 | 平均 43.05 s | 约 23,317 行/s |
| 异步导入 | 100,000 行，3 次 | 平均 22.42 s | 约 4,511 行/s |
| 异步导入 | 1,000,000 行 | 默认护栏拦截；放开护栏后在标准环境成功，平均约 4,521 行/s |

本机高配或本地 DB 历史参考：

| 场景 | 数据量 | 配置 | 结果 | 吞吐 |
| --- | ---: | --- | ---: | ---: |
| 导入 | 1,000,000 行 | 4 worker、2000 行/批 | 42,718 ms | 约 23,400 行/s |
| 导入 | 1,000,000 行 | 6 worker、2000 行/批 | 33,021 ms | 约 30,283 行/s |
| 导入 | 1,000,000 行 | 8 worker、2000 行/批 | 34,265 ms | 约 29,184 行/s |
| 导入 | 1,000,000 行 | 16 worker、2000 行/批 | 15,671 ms | 约 63,812 行/s |
| 导出 | 1,000,000 行 | 单任务、单 Sheet | 63,806 ms | 约 15,672 行/s |

性能结论：

- worker 数增加不等于线性提速。高配本地 DB 中 16 worker 最快，但标准小规格云服务器瓶颈在内存、云盘 fsync 和长事务。
- `IMPORT_WORKER_COUNT * IMPORT_MAX_CONCURRENT_TASKS <= HIKARI_MAXIMUM_POOL_SIZE` 只是硬约束；生产还要给查询、导出、健康检查和普通接口预留连接。
- 当前标准环境默认单次导入上限为 20 万行；百万级导入需要拆分任务，或在确认机器资源后放开 `IMPORT_MAX_ROWS_PER_TASK` 并重新压测。
- 导出 100 万级稳定，超过单 Sheet 上限会按设计失败。

运行压测脚本：

```bash
BASE_URL='<STANDARD_BASE_URL>' API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>' \
  python3 scripts/perf_bench.py --mode export --runs 3 --label exp-std

BASE_URL='<STANDARD_BASE_URL>' API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>' \
  python3 scripts/perf_bench.py --mode import --file /tmp/perf_100k.xlsx --runs 3 --label imp-std-100k
```

脚本使用 Python 标准库流式上传，输出任务处理耗时和行吞吐。
完整说明见：[docs/import-load-test.md](docs/import-load-test.md)。功能测试见：[docs/excel-import-export-test.md](docs/excel-import-export-test.md)。标准环境压测报告见：[docs/performance-report.md](docs/performance-report.md)。优化路径与复盘见：[docs/excel-import-export-optimization-review.md](docs/excel-import-export-optimization-review.md)。
