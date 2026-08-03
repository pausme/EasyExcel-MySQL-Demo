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
export MYSQL_PASSWORD='your_mysql_password'
```

导出任务状态存储在 Redis，默认连接：

```bash
export REDIS_HOST='your_redis_host'
export REDIS_PORT='your_redis_port'
```

导出文件上传到 MinIO，启动前还需要设置：

```bash
export MINIO_ENDPOINT='http://106.14.81.111:7000'
export MINIO_ACCESS_KEY='your_minio_access_key'
export MINIO_SECRET_KEY='your_minio_secret_key'
export MINIO_BUCKET_NAME='public'
```

下载接口会重定向到有效期为 30 分钟的 MinIO 签名地址。即使当前 Bucket 名称为 `public`，
也建议在生产环境中将 Bucket 设为私有。

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
MYSQL_URL='your_mysql_host:your_mysql_port' MYSQL_PASSWORD='your_mysql_password' mvn test
MYSQL_URL='your_mysql_host:your_mysql_port' MYSQL_PASSWORD='your_mysql_password' mvn spring-boot:run
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
请在 MinIO 为该前缀配置生命周期规则，自动清理超过保留期的导出文件。
任务状态 Redis key 前缀为 `excel:student:export:`，过期时间与 `app.excel.export-file-retention-hours` 一致。

导入使用 `student_no` 作为唯一业务键，重复导入同一个学生时会更新已有数据。
如果旧表中已经存在重复 `student_no`，需要先清理重复数据后再启动应用创建唯一索引。
