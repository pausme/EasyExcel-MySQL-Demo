# EasyExcel MySQL Demo

基于 Spring Boot、EasyExcel、MyBatis、MySQL、Redis 和 MinIO 的 Excel 导入导出演示项目。
重点演示大文件场景下的流式解析、批量写库、异步导入导出和对象存储。

## 功能概览

- Excel 导入：上传后先创建 IMPORT 任务，后台流式读取 Excel，分批放入有界队列，由多个 worker 批量写入 MySQL。
- Excel 导出：异步提交任务，使用游标分页读取数据库，生成单 Sheet Excel 后上传 MinIO。
- 异步任务中心：统一记录任务状态、进度、失败原因和重试次数，状态缓存 Redis，任务记录持久化 MySQL。
- 文件下载：应用只返回 MinIO 签名地址，不经过应用服务器转发大文件内容。
- 通用文件中心：支持普通上传、元数据查询、逻辑删除、分页查询和签名下载。
- 文件中心测试页：启动应用后访问 `/file-upload-test.html`，可测试秒传、客户端直传和分片上传。
- 数据更新：使用 `student_no` 作为唯一业务键，重复导入时更新已有记录。
- 压测工具：提供 Python 标准库脚本，支持并发矩阵和吞吐量统计。

## 实现思路

### 导入流程

```text
HTTP 上传 Excel
        |
        v
保存到本地导入临时目录
        |
        v
任务中心创建 IMPORT 任务，接口立即返回 taskId
        |
        v
后台导入线程读取临时文件
        |
        v
EasyExcel 流式解析
        |
        v
每 2000 行组成一个批次 -> 有界 BlockingQueue
        |
        v
多个导入 worker 消费批次并写入 student_import_stage
        |
        v
校验必填字段和文件内重复 student_no
        |
        v
单个事务从暂存表 upsert 到 student_record
```

导入不会把整份 Excel 加载到内存中。解析线程只保留当前批次，队列容量有限，队列满时解析会产生背压。
解析后的批次先写入 `student_import_stage` 暂存表，全部解析和暂存成功后，再通过一个数据库事务合并到 `student_record`。
如果 Excel 后半段解析失败、暂存失败、必填字段为空、文件内出现重复 `student_no`，正式表都不会被修改。
导入任务进度会写入统一任务中心：`totalCount` 表示已经解析的行数，`completedCount` 表示已经暂存的行数，任务完成前进度最高到 95%，成功后为 100%。

导入 worker 的数量、导入任务并发数和数据库连接池相互约束：

```text
IMPORT_WORKER_COUNT * IMPORT_MAX_CONCURRENT_TASKS <= HIKARI_MAXIMUM_POOL_SIZE
```

默认只允许一个导入任务执行。导入失败时会清空待处理队列、取消 worker，并等待已经启动的 worker 收尾，最后清理本次导入的暂存数据。
数据库瞬时异常会按配置进行有限重试。
取消导入是尽力而为：解析线程和 worker 会在批次边界检查任务状态；如果取消发生在最终合并事务已经提交之后，正式表变更不会自动撤销。

### 导出流程

```text
提交导出请求
        |
        v
任务中心创建 EXPORT 任务，MySQL 持久化记录，Redis 缓存状态
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
导入和导出任务都已经接入统一任务中心，进度、失败原因、取消和重试都会同步到 `async_task_record`，并缓存到 Redis。
为了让导出的文件可以直接作为导入文件使用，当前导出只生成一个 Sheet；超过 Excel 单 Sheet 行数限制时任务失败。

## 项目结构

```text
src/main/java/com/huang/demo
├── DemoApplication.java
├── excel
    ├── config        # 配置属性和导入导出线程池
    ├── controller    # Excel HTTP 接口
    ├── listener      # EasyExcel 流式导入监听器
    ├── model         # Excel 行模型
    ├── domain/model  # 领域模型和任务结果
    ├── repository    # MyBatis Mapper
    └── service       # 导入导出业务编排
├── file              # 通用文件上传中心
└── task              # 统一异步任务中心

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

# 异步任务中心
export TASK_CENTER_INIT_ENABLED='true'
export TASK_CENTER_REDIS_KEY_PREFIX='task:center:'
export TASK_CENTER_CACHE_RETENTION_HOURS='24'
export TASK_CENTER_MAX_PAGE_SIZE='100'
export TASK_CENTER_DEFAULT_OWNER_ID='anonymous'
export TASK_CENTER_MAX_RETRY_COUNT='3'

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
export FILE_CENTER_CORS_ENABLED='true'
export FILE_CENTER_CORS_ALLOWED_ORIGIN_PATTERNS='http://localhost:*,http://127.0.0.1:*,null'
```

建议将 MinIO Bucket 设置为私有。导出下载接口返回有效期默认 30 分钟的签名地址，避免大文件流量经过应用服务器。
导出对象默认写入 `excel/student/` 前缀，生命周期规则默认在 1 天后清理对象。
通用文件中心默认写入 `files/general/` 前缀，可通过 `FILE_CENTER_OBJECT_PREFIX` 调整。

## 数据库初始化

脚本位于 `src/main/resources/db/mysql`：

```text
create_database.sql  # 创建 demo 数据库
create_tables.sql    # 创建业务表和任务表
schema.sql            # 完整结构脚本
```

如果 SQL 客户端不能稳定执行多语句脚本，建议先执行 `create_database.sql`，再选择 `demo` 数据库执行 `create_tables.sql`。
`create_tables.sql` 会同时创建 `student_record`、`student_import_stage`、`file_record`、`file_upload_task` 和 `async_task_record`。

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
| POST | `/import` | 上传 Excel 并提交异步导入任务，字段名为 `file` |

基础路径：`/api/tasks`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/{taskId}` | 查询自己的异步任务详情 |
| POST | `/page` | 分页查询自己的异步任务 |
| POST | `/{taskId}/cancel` | 取消自己的异步任务 |
| POST | `/{taskId}/retry` | 重试自己的异步任务 |

任务归属通过请求头 `X-User-Id` 区分；当前项目没有登录系统，不传时使用 `TASK_CENTER_DEFAULT_OWNER_ID`。
学生导入和导出任务都可以通过 `/api/tasks` 查询、取消和重试。`/api/excel/export/{taskId}` 仍保留导出专用状态接口，方便直接获取导出下载信息。
导入接口提交成功后立即返回任务 ID，任务完成后通过 `/api/tasks/{taskId}` 查看最终状态和结果；导出接口同样先返回任务 ID，完成后再调用状态接口和下载接口。

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

### 接口总览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/upload` | 文件经过后端上传，适合小文件或后端需要读取文件内容的场景 |
| `POST` | `/instant-check` | 根据文件 MD5 和文件大小检查是否可以秒传 |
| `POST` | `/direct/init` | 初始化整文件客户端直传，返回一个 MinIO PUT 签名地址 |
| `POST` | `/direct/{uploadId}/complete` | 校验直传对象并生成正式文件记录 |
| `POST` | `/multipart/init` | 初始化分片上传，返回每个分片的 MinIO PUT 签名地址 |
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
export IMPORT_TASK_CORE_POOL_SIZE='1'
export IMPORT_TASK_MAX_POOL_SIZE='1'
export IMPORT_TASK_QUEUE_CAPACITY='10'
export IMPORT_TEMP_DIR='/tmp/student-excel-import'
```

`IMPORT_AWAIT_TERMINATION_SECONDS` 只控制应用停机时等待线程池退出。
`IMPORT_TASK_*` 控制真正执行异步导入任务的外层线程池；每个导入任务内部还会按 `IMPORT_WORKER_COUNT` 启动写库 worker。
`IMPORT_WORKER_FINISH_WAIT_SECONDS` 控制导入任务等待 worker 收尾，默认 `0` 表示不主动超时。
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

- 导入采用“暂存表 + 校验后单事务合并”策略，正式表层面满足全量原子性。
- 暂存表写入可以分批完成；只要最终校验或合并失败，本次导入不会修改 `student_record`。
- 同一个文件内出现重复 `student_no` 会直接失败，避免不同批次提交顺序影响最终结果。
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

四次 100 万条导入均没有出现失败、死锁、重试或超时；8 个 worker 的单次结果略慢于 6 个 worker，说明 worker 数增加不必然带来收益，应在相同环境下重复测试后再确定最优值。16 个 worker 相比 6 个 worker 的耗时减少约 52.5%，但需要数据库连接池至少能够支撑对应的写库线程，并为其他业务连接预留余量。实际收益仍取决于数据库连接池、CPU 和磁盘 IO。导出成功写入 1 个 Sheet。导入结果中的 `imported` 表示处理的 Excel 行数；如果这些学号已存在于正式表，会更新已有记录而不是新增重复记录。

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
