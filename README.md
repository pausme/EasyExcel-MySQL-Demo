# EasyExcel MySQL Demo

基于 Spring Boot、EasyExcel、MyBatis、MySQL、Redis 和 MinIO 的 Excel 导入导出演示项目。
重点演示大文件场景下的流式解析、批量写库、异步导出和对象存储。

## 功能概览

- Excel 导入：流式读取 Excel，分批放入有界队列，由多个 worker 批量写入 MySQL。
- Excel 导出：异步提交任务，使用游标分页读取数据库，生成单 Sheet Excel 后上传 MinIO。
- 文件下载：应用只返回 MinIO 签名地址，不经过应用服务器转发大文件内容。
- 通用文件中心：支持普通上传、元数据查询、逻辑删除、分页查询和签名下载。
- 数据更新：使用 `student_no` 作为唯一业务键，重复导入时更新已有记录。
- 压测工具：提供 Python 标准库脚本，支持并发矩阵和吞吐量统计。

## 实现思路

### 导入流程

```text
HTTP 上传 Excel
        |
        v
EasyExcel 流式解析
        |
        v
每 2000 行组成一个批次 -> 有界 BlockingQueue
        |
        v
多个导入 worker 消费批次
        |
        v
事务批量 upsert 到 student_record
```

导入不会把整份 Excel 加载到内存中。解析线程只保留当前批次，队列容量有限，队列满时解析会产生背压。
每个批次使用独立事务，SQL 使用 `INSERT ... ON DUPLICATE KEY UPDATE`，因此适合吞吐优先的批量回导。

导入 worker 的数量、导入任务并发数和数据库连接池相互约束：

```text
IMPORT_WORKER_COUNT * IMPORT_MAX_CONCURRENT_TASKS <= HIKARI_MAXIMUM_POOL_SIZE
```

默认只允许一个导入任务执行。导入失败时会清空待处理队列、取消 worker，并等待已经启动的 worker 收尾。
数据库瞬时异常会按配置进行有限重试，批次写入前可以按 `student_no` 排序以降低死锁概率。

### 导出流程

```text
提交导出请求
        |
        v
Redis 保存任务状态，线程池异步执行
        |
        v
记录当前 MAX(id)，按 id 游标分页读取
        |
        v
EasyExcel 写入单 Sheet 临时文件
        |
        v
上传 MinIO，删除本地临时文件
        |
        v
查询任务状态并返回 MinIO 签名下载地址
```

导出使用 `id > lastId AND id <= maxId` 的游标条件，避免大 offset 分页越来越慢，且保证一次导出有固定的数据边界。
为了让导出的文件可以直接作为导入文件使用，当前导出只生成一个 Sheet；超过 Excel 单 Sheet 行数限制时任务失败。

## 项目结构

```text
src/main/java/com/huang/demo
├── DemoApplication.java
└── excel
    ├── config        # 配置属性和导入导出线程池
    ├── controller    # Excel HTTP 接口
    ├── listener      # EasyExcel 流式导入监听器
    ├── model         # Excel 行模型
    ├── domain/model  # 领域模型和任务结果
    ├── repository    # MyBatis Mapper
    └── service       # 导入导出业务编排

src/main/resources
├── mapper            # MyBatis XML
└── db/mysql          # 数据库初始化脚本

scripts/import_load_test.py       # 导入压测脚本
docs/excel-import-export-test.md  # 功能测试文档
docs/import-load-test.md          # 压测使用说明
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

# MinIO
export MINIO_ENDPOINT='http://<MinIO地址>:<MinIO API端口>'
export MINIO_ACCESS_KEY='your_minio_access_key'
export MINIO_SECRET_KEY='your_minio_secret_key'
export MINIO_BUCKET_NAME='public'
export FILE_CENTER_INIT_ENABLED='true'
export FILE_CENTER_OBJECT_PREFIX='files/general'
export FILE_CENTER_MULTIPART_OBJECT_PREFIX='files/multipart'
export FILE_CENTER_DOWNLOAD_URL_EXPIRE_MINUTES='30'
export FILE_CENTER_UPLOAD_URL_EXPIRE_MINUTES='30'
export FILE_CENTER_MULTIPART_PART_SIZE='8388608'
export FILE_CENTER_MULTIPART_MAX_PART_COUNT='1000'
export FILE_CENTER_MAX_PAGE_SIZE='100'
```

建议将 MinIO Bucket 设置为私有。导出下载接口返回有效期默认 30 分钟的签名地址，避免大文件流量经过应用服务器。
导出对象默认写入 `excel/student/` 前缀，生命周期规则默认在 1 天后清理对象。
通用文件中心默认写入 `files/general/` 前缀，可通过 `FILE_CENTER_OBJECT_PREFIX` 调整。

## 数据库初始化

脚本位于 `src/main/resources/db/mysql`：

```text
create_database.sql  # 创建 demo 数据库
create_tables.sql    # 创建 student_record 表
schema.sql            # 完整结构脚本
```

如果 SQL 客户端不能稳定执行多语句脚本，建议先执行 `create_database.sql`，再选择 `demo` 数据库执行 `create_tables.sql`。

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
| POST | `/import` | 上传 Excel，字段名为 `file` |

基础路径：`/api/files`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/upload` | 上传通用文件 |
| POST | `/instant-check` | 根据 MD5 和文件大小秒传检查 |
| POST | `/direct/init` | 初始化客户端直传，返回 MinIO PUT 签名地址 |
| POST | `/direct/{uploadId}/complete` | 客户端直传完成后确认落库 |
| POST | `/multipart/init` | 初始化客户端分片上传，返回每个分片的 PUT 签名地址 |
| GET | `/multipart/{uploadId}/parts` | 查询已经上传到 MinIO 的分片序号 |
| POST | `/multipart/{uploadId}/complete` | 合并分片并生成正式文件记录 |
| POST | `/multipart/{uploadId}/abort` | 取消分片任务并清理已上传分片 |
| GET | `/{fileId}` | 查询文件详情 |
| GET | `/{fileId}/download` | 获取 302 MinIO 签名下载地址 |
| POST | `/{fileId}/delete` | 逻辑删除文件 |
| POST | `/page` | 分页查询文件 |

客户端直传流程：

```text
POST /api/files/instant-check
        |
        |-- exists=true  直接复用返回的 fileId
        |
        |-- exists=false
             POST /api/files/direct/init
             PUT uploadUrl 到 MinIO
             POST /api/files/direct/{uploadId}/complete
```

客户端分片上传流程：

```text
POST /api/files/multipart/init
        |
        v
并发 PUT 每个 part 的 uploadUrl 到 MinIO
        |
        v
可用 GET /api/files/multipart/{uploadId}/parts 断点续传
        |
        v
POST /api/files/multipart/{uploadId}/complete 合并分片
```

导入成功响应包含：`imported`、`batchCount`、`count` 和 `elapsedMs`。
导出接口先返回任务 ID，任务完成后再调用状态接口和下载接口。

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
```

`IMPORT_AWAIT_TERMINATION_SECONDS` 只控制应用停机时等待线程池退出。
`IMPORT_WORKER_FINISH_WAIT_SECONDS` 控制接口等待 worker 收尾，默认 `0` 表示不主动超时。
如果设置为正数，应用启动时会校验它覆盖单批事务和重试时间窗口。

导出线程池可以通过以下变量调整：

```bash
export EXPORT_CORE_POOL_SIZE='2'
export EXPORT_MAX_POOL_SIZE='2'
export EXPORT_QUEUE_CAPACITY='10'
export EXPORT_AWAIT_TERMINATION_SECONDS='30'
export EXPORT_REJECTED_EXECUTION_POLICY='abort'
```

## 一致性和容量边界

- 导入是批次独立事务，后续批次失败时，已经提交的前序批次不会自动回滚。
- 如果需要全量原子性、失败行明细和断点续传，应使用“导入任务表 + 暂存表 + 校验后合并”。
- 同一个 `student_no` 出现在不同导入批次时，最终值取决于批次提交顺序，生产环境建议先校验文件内重复数据。
- 当前导出只使用一个 Sheet，单 Sheet 数据行上限为 `1048575`。
- MinIO 对象由 Bucket 生命周期规则清理，Redis 任务状态过期不会自动删除已经上传的对象。
- 导入内存主要由当前批次、有限队列和并发任务数量决定；增加 worker 或并发任务会增加数据库连接和内存压力。

## 性能基线和压测

最新实测记录（2026-08-04 至 2026-08-05）：

| 场景 | 数据量 | 配置 | 结果 | 吞吐量 |
| --- | ---: | --- | ---: | ---: |
| 导入 | 1,000,000 行 | 4 个 worker、单任务并发、2000 行/批 | 42,718 ms，500 批 | 约 23,400 行/秒 |
| 导入 | 1,000,000 行 | 6 个 worker、单任务并发、2000 行/批 | 33,021 ms，500 批 | 约 30,283 行/秒 |
| 导入 | 1,000,000 行 | 8 个 worker、单任务并发、2000 行/批 | 34,265 ms，500 批 | 约 29,184 行/秒 |
| 导入 | 1,000,000 行 | 16 个 worker、单任务并发、2000 行/批 | 15,671 ms，500 批 | 约 63,812 行/秒 |
| 导出 | 1,000,000 行 | 单任务、单 Sheet | 63,806 ms | 约 15,672 行/秒 |

四次 100 万条导入均没有出现失败、死锁、重试或超时；8 个 worker 的单次结果略慢于 6 个 worker，说明 worker 数增加不必然带来收益，应在相同环境下重复测试后再确定最优值。16 个 worker 相比 6 个 worker 的耗时减少约 52.5%，但需要数据库连接池至少能够支撑对应的写库线程，并为其他业务连接预留余量。实际收益仍取决于数据库连接池、CPU 和磁盘 IO。导出成功写入 1 个 Sheet。导入结果中的 `imported` 表示处理的 Excel 行数，重复 `student_no` 可能是更新而不是新增。

历史基线：

- 100 万条导入：约 `35272 ms`，`batchCount=500`；
- 100 万条导出：约 `59151 ms`。

运行压测脚本：

```bash
python3 scripts/import_load_test.py \
  --base-url 'http://<应用地址>:<应用端口>' \
  --file ./student-112000.xlsx \
  --matrix 1,2,4 \
  --requests 4 \
  --output ./import-load-matrix.json
```

脚本使用 Python 标准库流式上传，输出成功率、总耗时、请求吞吐和行吞吐。
完整说明见：[docs/import-load-test.md](docs/import-load-test.md)。功能测试见：[docs/excel-import-export-test.md](docs/excel-import-export-test.md)。优化路径与复盘见：[docs/excel-import-export-optimization-review.md](docs/excel-import-export-optimization-review.md)。
