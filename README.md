# EasyExcel MySQL Demo

基于 Spring Boot、EasyExcel、MyBatis 的 Excel 导入导出演示项目。

## 项目结构

```text
src/main/java/com/huang/demo
├── DemoApplication.java
└── excel
    ├── config        # 导入导出批次、初始化数据量配置
    ├── controller    # HTTP 接口
    ├── listener      # EasyExcel 导入监听器
    ├── model         # Excel 行模型
    ├── repository    # MyBatis Mapper 接口
    └── service       # 业务编排和计时日志
```

MyBatis XML 位于：

```text
src/main/resources/mapper/StudentMapper.xml
```

## 环境变量

数据库密码不写入配置文件，启动前需要设置：

```bash
export MYSQL_URL='your_mysql_host:your_mysql_port'
export MYSQL_USERNAME='your_mysql_username'
export MYSQL_PASSWORD='your_mysql_password'
```

导出任务状态存储在 Redis，启动前需要设置连接信息：

```bash
export REDIS_HOST='your_redis_host'
export REDIS_PORT='your_redis_port'
export REDIS_DATABASE='your_redis_database'
export REDIS_PASSWORD='your_redis_password'
```

导出文件上传到 MinIO，启动前还需要设置：

```bash
export MINIO_ENDPOINT='http://your-minio-host:7000'
export MINIO_ACCESS_KEY='your_minio_access_key'
export MINIO_SECRET_KEY='your_minio_secret_key'
export MINIO_BUCKET_NAME='student-excel'
```

建议将 Bucket 设为私有。下载接口会返回 302，重定向到有效期为 30 分钟的 MinIO 签名地址，
避免大文件下载占用应用服务器带宽和 Tomcat 工作线程。

导出对象默认写入 `excel/student/` 前缀。应用启动时会尽力为该前缀配置 MinIO 生命周期规则，
默认 1 天后自动删除导出对象：

```bash
export MINIO_LIFECYCLE_ENABLED='true'
export MINIO_LIFECYCLE_EXPIRE_DAYS='1'
```

如果 Bucket 已有其他生命周期规则，应用只会替换规则 ID 为 `student-excel-export-retention` 的规则。
生产环境也可以直接在 MinIO 控制台维护生命周期规则。

## 数据库脚本

脚本目录：

```text
src/main/resources/db/mysql
├── create_database.sql
├── create_tables.sql
└── schema.sql
```

如果 SQL 控制台不能稳定执行多语句脚本，推荐先执行 `create_database.sql`，选中 `demo` schema 后再执行 `create_tables.sql`。

## 启动和测试

```bash
MYSQL_URL='your_mysql_host:your_mysql_port' MYSQL_USERNAME='your_mysql_username' MYSQL_PASSWORD='your_mysql_password' mvn test
MYSQL_URL='your_mysql_host:your_mysql_port' MYSQL_USERNAME='your_mysql_username' MYSQL_PASSWORD='your_mysql_password' mvn spring-boot:run
```

大文件回导的 multipart 限制默认是 200MB，可按实际导出文件体积调整：

```bash
export SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE='200MB'
export SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE='200MB'
```

## 接口

```text
GET  /api/excel/count
POST /api/excel/export                 提交异步导出任务
GET  /api/excel/export/{taskId}        查询导出任务状态
GET  /api/excel/export/{taskId}/download
GET  /api/excel/template               下载导入模板
POST /api/excel/import                  multipart file 字段名: file
POST /api/excel/seed/{count}
```

导出任务会在后台使用 `id` 游标分页，导出开始时记录 `MAX(id)` 作为边界。
为了保证导出的 Excel 可以直接作为导入文件，导出只生成单个 Sheet；
单 Sheet 最多写入 1048575 条数据，超过时导出任务会失败并提示改用 CSV 或缩小导出范围。
文件会先生成到 `app.excel.export-temp-dir` 配置的本地临时目录，再上传到 MinIO。
上传成功后会删除本地临时文件，对象路径前缀由 `MINIO_EXPORT_OBJECT_PREFIX` 配置。
MinIO 对象清理由 Bucket 生命周期规则负责，Redis 任务状态过期不会删除已经上传的对象。
任务状态 Redis key 前缀为 `excel:student:export:`，过期时间与 `app.excel.export-file-retention-hours` 一致。

异步导出线程池可通过环境变量调整：

```bash
export EXPORT_CORE_POOL_SIZE='2'
export EXPORT_MAX_POOL_SIZE='2'
export EXPORT_QUEUE_CAPACITY='10'
export EXPORT_AWAIT_TERMINATION_SECONDS='30'
export EXPORT_REJECTED_EXECUTION_POLICY='abort'
```

拒绝策略支持 `abort` 和 `caller-runs`。默认 `abort` 会让提交失败并把任务标记为失败；
`caller-runs` 会让提交请求线程执行导出任务，通常只适合本地调试。

导入使用 `student_no` 作为唯一业务键，重复导入同一个学生时会更新已有数据。
如果旧表中已经存在重复 `student_no`，需要先清理重复数据后再启动应用创建唯一索引。
导入接口采用生产者/消费者模型：EasyExcel 解析时每累计 `app.excel.import-batch-size`
行放入阻塞队列，多个写库线程并发消费，每个批次使用独立事务提交。该模式优先提升吞吐，
文件后半段解析或写库失败时，已经提交成功的批次不会回滚。
如果同一个 `student_no` 在不同批次重复出现，最终值取决于批次提交顺序；生产环境应在导入前校验文件内唯一性。

导入线程池可通过环境变量调整：

```bash
export IMPORT_WORKER_COUNT='4'
export IMPORT_QUEUE_CAPACITY='20'
export IMPORT_EXECUTOR_QUEUE_CAPACITY='20'
export IMPORT_AWAIT_TERMINATION_SECONDS='30'
export IMPORT_MAX_RETRY_TIMES='3'
export IMPORT_RETRY_BACKOFF_MILLIS='200'
```

并发写入 `INSERT ... ON DUPLICATE KEY UPDATE` 时，MySQL 可能因为唯一索引锁竞争产生瞬时死锁。
导入批次会按 `student_no` 排序后写入，并对死锁等瞬时数据库异常进行有限重试。

百万级生产导入如果需要全量原子性、失败行明细、断点续传和人工修正，建议改为
“导入任务表 + 暂存表 + 校验通过后合并”的方案。

## 性能记录

2026-08-04 实测：

- 100 万条数据导入：`35272 ms`
- `batchCount=500`
- 100 万条数据导出：`59151 ms`

当前导入采用 `2000` 行一批、阻塞队列、多 worker 独立事务写库的方式，性能会随
`IMPORT_WORKER_COUNT`、`IMPORT_QUEUE_CAPACITY`、`IMPORT_MAX_RETRY_TIMES` 和数据库状态变化。
