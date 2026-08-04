# Excel 导入导出测试文档

## 1. 目的和范围

验证学生数据的异步导出、MinIO 文件保存、签名下载地址、Excel 导入，以及重复学号更新行为。

接口根路径：`http://<应用地址>:18088/api/excel`

## 2. 测试环境

测试前设置数据库、Redis 和 MinIO 环境变量。当前 MinIO 桶默认配置为 `student-excel`；如果继续使用已创建的 `public` 桶，必须显式设置 `MINIO_BUCKET_NAME=public`。

```bash
export MYSQL_URL='your-server-host:13306'
export MYSQL_PASSWORD='<数据库密码>'
export REDIS_HOST='your-server-host'
export REDIS_PORT='16379'
export MINIO_ENDPOINT='http://your-minio-host:7000'
export MINIO_ACCESS_KEY='<MinIO Access Key>'
export MINIO_SECRET_KEY='<MinIO Secret Key>'
export MINIO_BUCKET_NAME='public'
```

确认 MinIO API 健康检查正常：

```bash
curl http://your-minio-host:7000/minio/health/live
```

预期：返回 HTTP `200`。

生产环境建议使用私有桶。Bucket 名为 `public` 不等于桶一定公开，但若配置了匿名读策略，学生导出文件可绕过签名 URL 被直接访问。

## 3. 基础检查

| 编号 | 操作 | 预期结果 |
| --- | --- | --- |
| B-01 | 执行 `mvn.cmd test` | 通过，Spring 上下文可启动 |
| B-02 | 调用 `GET /count` | 返回当前学生总数 `count` |
| B-03 | 查看应用启动日志 | 出现 `minio lifecycle configured`；若为 `configure minio lifecycle failed`，需检查 MinIO 权限和 Bucket 生命周期配置 |
| B-04 | 在 MinIO 控制台检查生命周期规则 | `excel/student/` 前缀存在 ID 为 `student-excel-export-retention` 的过期规则，默认 1 天 |

## 4. 导出测试

### 4.1 提交和查询任务

1. 调用：

```bash
curl -X POST http://<应用地址>:18088/api/excel/export
```

2. 保存响应中的 `taskId`。
3. 轮询：

```bash
curl http://<应用地址>:18088/api/excel/export/<taskId>
```

预期：状态按 `QUEUED -> RUNNING -> SUCCESS` 变化；成功时满足：

- `exported` 等于 `total`；
- `sheetCount` 为 `1`；
- `fileName` 以 `.xlsx` 结尾；
- `errorMessage` 为空。

### 4.2 下载文件

```bash
curl -I http://<应用地址>:18088/api/excel/export/<taskId>/download
```

预期：返回 HTTP `302`，并包含 `Location` 响应头。浏览器或 HTTP 客户端跟随该地址后应下载 xlsx 文件。

下载后检查：

- 文件可由 Excel/WPS 打开；
- 仅有一个 Sheet，名称为“学生数据”；
- 表头与导入模板一致；
- 行数等于任务状态中的 `exported` 加 1 个表头；
- MinIO 中存在 `excel/student/student-demo-<taskId>.xlsx` 对象。

### 4.3 签名地址过期

1. 将 `MINIO_DOWNLOAD_URL_EXPIRE_MINUTES` 临时设为 `1` 并重启应用。
2. 获取下载接口的 `Location` 地址。
3. 在 1 分钟内访问该地址，应正常下载。
4. 超过 1 分钟再次访问同一地址。

预期：过期后的签名地址被 MinIO 拒绝；重新调用应用下载接口会生成新的有效地址。

### 4.4 导出边界

| 编号 | 场景 | 预期结果 |
| --- | --- | --- |
| E-01 | 空表导出 | 生成只含表头的单 Sheet 文件，任务成功 |
| E-02 | 112000 条数据导出 | 任务成功，全部数据在同一个 Sheet |
| E-03 | 数据量超过 1048575 条 | 任务失败，错误信息提示改用 CSV 或缩小范围 |
| E-04 | MinIO 不可用 | 任务状态为 `FAILED`，本地 `.part` 临时文件会被删除 |
| E-05 | 任务队列已满且拒绝策略为 `abort` | 新任务状态为 `FAILED`，错误信息为“导出任务提交失败” |

## 5. 导入测试

### 5.1 下载模板和新增数据

1. 下载模板：

```bash
curl -OJ http://<应用地址>:18088/api/excel/template
```

2. 填入一条此前不存在的学号，例如 `S900001`。
3. 上传：

```bash
curl -X POST http://<应用地址>:18088/api/excel/import \
  -F "file=@student-import-template.xlsx"
```

预期：

- `imported` 为 Excel 数据行数；
- `batchCount` 大于 0；
- `count` 比导入前增加新增学号数量；
- 数据库存在该学号记录。

### 5.2 导出后修改再导入

1. 完成一次导出并下载文件。
2. 修改某条已有数据的姓名、班级或邮箱，保持 `student_no` 不变。
3. 上传修改后的文件。
4. 使用 SQL 验证：

```sql
SELECT student_no, name, class_name, email
FROM student_record
WHERE student_no = '<修改的学号>';
```

预期：字段更新为 Excel 中的新值；同一学号不会新增重复记录；整份文件回导后总数保持不变。

### 5.3 多 Sheet 文件

上传一个包含两个以上 Sheet 的合法 Excel，且每个 Sheet 都使用相同表头。

预期：所有 Sheet 均被读取，`imported` 为所有 Sheet 数据行数之和。

### 5.4 导入边界与异常

| 编号 | 场景 | 预期结果 |
| --- | --- | --- |
| I-01 | 未携带 `file` 参数 | HTTP `400` |
| I-02 | 非 Excel 文件或损坏文件 | 接口失败，日志包含解析异常 |
| I-03 | 文件超过 200MB | HTTP `413` |
| I-04 | 空模板直接上传 | 成功返回，`imported=0`、`batchCount=0` |
| I-05 | 同一文件出现重复 `student_no` | 以文件中最后被处理的数据为准；建议业务侧增加重复行校验 |
| I-06 | 第 N 批发生数据库异常 | 已成功提交的前序批次不会自动回滚，需要记录实际落库数量并人工清理或重试 |

## 6. 性能测试

| 场景 | 数据量 | 关注指标 | 建议验收 |
| --- | --- | --- | --- |
| P-01 | 112000 条导出 | 总耗时、CPU、内存、临时磁盘空间、MinIO 上传耗时 | 与当前基线对比，不出现 OOM 或任务失败 |
| P-02 | 112000 条下载 | 应用服务器带宽 | 下载请求返回 302，文件流量不经过应用服务器 |
| P-03 | 112000 条回导 | 总耗时、批次数、数据库连接数 | `imported=112000`，总数不因重复学号而增长 |
| P-04 | 同时提交 3 个导出任务 | 排队状态、线程数、临时目录占用 | 不超过配置的线程池和队列限制，任务状态准确 |

## 7. 测试记录

| 日期 | 环境 | 用例编号 | 结果 | 任务 ID/备注 | 执行人 |
| --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |
