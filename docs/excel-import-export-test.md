# Excel 导入导出测试说明

本文用于说明 Excel 导入导出模块的测试范围、执行方式、验收标准和当前测试结论。更完整的接口扁平化执行记录见 [docs/test/测试执行记录.md](test/测试执行记录.md)，当前权威结果以 R11 为准；性能复盘见 [docs/performance-report.md](performance-report.md)。

## 1. 测试范围

覆盖以下能力：

- 异步导出：任务提交、状态查询、版本快照、单 Sheet 生成、MinIO 上传、302 签名下载。
- 异步导入：模板下载、源文件上传 MinIO、后台解析、暂存表写入、全量校验、分块构建新版本、可见版本切换。
- 数据一致性：`student_no` 唯一键 upsert、重复学号拦截、失败时正式表不被污染。
- 任务能力：统一任务中心查询、取消、重试、失败原因、进度。
- 权限隔离：关闭 demo 模式后按 Bearer Token 识别用户，用户只能访问自己的任务和文件。

接口根路径：`http://<应用地址>:<应用端口>/api/excel`。

## 2. 环境准备

测试环境必须显式提供数据库、Redis、MinIO 和鉴权配置，文档中只保留占位符，不写入真实地址或密钥。

如需在本机启动隔离的 MySQL、Redis、MinIO 并自动执行接口联调，可直接运行：

```bash
scripts/run_integration_tests.sh
```

该脚本会使用 `docker-compose-test.yml` 启动测试依赖，打包并启动应用，再调用 `scripts/run_flat_tests.py`。执行完成后默认清理容器和数据卷。

```bash
export SERVER_PORT='<应用端口>'

export MYSQL_URL='<数据库地址>:<数据库端口>'
export MYSQL_USERNAME='<数据库用户名>'
export MYSQL_PASSWORD='<数据库密码>'
export HIKARI_MAXIMUM_POOL_SIZE='<连接池最大连接数>'

export REDIS_HOST='<Redis地址>'
export REDIS_PORT='<Redis端口>'
export REDIS_DATABASE='<Redis库>'
export REDIS_PASSWORD='<Redis密码>'

export MINIO_ENDPOINT='http://<MinIO内部地址>:<MinIO API端口>'
export MINIO_PUBLIC_ENDPOINT='http://<MinIO公网或浏览器可访问地址>:<MinIO API端口>'
export MINIO_ACCESS_KEY='<MinIO Access Key>'
export MINIO_SECRET_KEY='<MinIO Secret Key>'
export MINIO_BUCKET_NAME='student-excel'

export API_SECURITY_DEMO_MODE='false'
export API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>'
export API_SECURITY_DEMO_ADMIN_TOKEN='<ADMIN_TOKEN>'
```

生产或准生产测试建议：

- MinIO Bucket 使用私有策略，下载和上传都走签名 URL。
- 应用、MySQL、Redis、MinIO 同机或同内网部署，避免把导入写库性能误判成公网网络问题。
- 小规格单机 Docker 环境需要 swap 兜底；百万级导入前应先评估内存、云盘 fsync 和事务超时。

## 3. 基础检查

| 编号 | 操作 | 预期 |
| --- | --- | --- |
| B-01 | 执行 `mvn test` | 单元/切片测试通过 |
| B-02 | `GET /api/excel/count` | 返回统一响应，`data.count` 为当前学生数 |
| B-03 | 查看启动日志 | MinIO 生命周期配置成功或给出可排查告警 |
| B-04 | 查看 Bucket 生命周期 | `excel/student/`、`excel/student/import-source/`、`excel/student/import-error/` 前缀按配置过期 |
| B-05 | 无 Token 访问受保护接口 | demo 模式关闭时返回 401 |

## 4. 导出测试

### 4.1 正常导出

```bash
curl -X POST '<BASE_URL>/api/excel/export' \
  -H 'Authorization: Bearer <USER_TOKEN>'
```

保存响应中的 `taskId`，轮询：

```bash
curl '<BASE_URL>/api/excel/export/<taskId>' \
  -H 'Authorization: Bearer <USER_TOKEN>'
```

预期：

- 状态从 `CREATED/QUEUED` 进入 `RUNNING`，最终为 `SUCCESS`。
- `exported == total`。
- `sheetCount == 1`。
- `fileName` 以 `.xlsx` 结尾。
- `errorMessage` 为空。

下载接口：

```bash
curl -I '<BASE_URL>/api/excel/export/<taskId>/download' \
  -H 'Authorization: Bearer <USER_TOKEN>'
```

预期返回 HTTP `302`，`Location` 为 MinIO 签名 URL。文件流量不经过 Spring Boot。

### 4.2 导出边界

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| E-01 | 空表导出 | 成功生成只有表头的 Excel |
| E-02 | 10 万级导出 | 成功，单 Sheet |
| E-03 | 100 万级且不超过单 Sheet 上限 | 成功，单 Sheet |
| E-04 | 超过 `1,048,575` 数据行 | 任务失败，提示缩小范围或改用 CSV |
| E-05 | 对失败任务请求下载 | 返回 409，不生成下载地址 |
| E-06 | MinIO 不可用 | 任务失败，记录外部依赖失败原因 |

## 5. 导入测试

### 5.1 模板下载和小数据导入

```bash
curl -OJ '<BASE_URL>/api/excel/template' \
  -H 'Authorization: Bearer <USER_TOKEN>'
```

填入新学号后上传：

```bash
curl -X POST '<BASE_URL>/api/excel/import' \
  -H 'Authorization: Bearer <USER_TOKEN>' \
  -F 'file=@student-import-template.xlsx'
```

预期：

- 接口立即返回导入任务 ID。
- 通过 `/api/excel/import/{taskId}` 或 `/api/tasks/{taskId}` 查询最终状态。
- 成功时 `imported` 等于 Excel 数据行数。
- 正式表按 `student_no` 新增或更新。

### 5.2 暂存校验和分块合并导入

导入流程为：

```text
上传源文件到 MinIO
    -> 后台读取源文件
    -> EasyExcel 流式解析
    -> 每 2000 行入队
    -> 多 worker 分批写入 student_import_stage
    -> 校验必填、长度、格式、文件内重复 student_no
    -> 按 IMPORT_MERGE_CHUNK_SIZE 分块写入 student_record 新版本
    -> CAS 发布 student_import_version_control.current_version
```

验收：

- Excel 后半段解析失败，正式表不发生本次导入变更。
- 暂存阶段失败，正式表不发生本次导入变更。
- 最终校验失败，正式表不发生本次导入变更。
- 同一文件出现重复 `student_no`，任务失败并生成错误明细文件。
- 失败后 `student_import_stage` 不长期残留本次任务数据。
- 构建新版本阶段使用多个短事务；如果中途数据库异常或版本发布 CAS 失败，当前可见版本不变，未发布版本按 `import_task_id` 清理。

### 5.3 导入边界

| 编号 | 场景 | 预期 |
| --- | --- | --- |
| I-01 | 未携带 `file` part | 返回 400 + `COMMON_PARAM_ERROR` |
| I-02 | Content-Type 不支持 | 返回 415 + `COMMON_PARAM_ERROR` |
| I-03 | 非 Excel 或损坏文件 | 提交阶段返回 400，不创建异步任务，不暴露内部堆栈 |
| I-04 | 文件超过 multipart 限制 | HTTP 413 |
| I-05 | 空模板上传 | 成功，`imported=0` |
| I-06 | 文件内重复学号 | 任务失败，生成错误文件，正式表不变 |
| I-07 | 10 万行导入 | 当前标准环境 3/3 成功 |
| I-08 | 100 万行导入 | 默认受 `IMPORT_MAX_ROWS_PER_TASK` 护栏限制；放开限制前需确认机器内存、swap、worker、连接池和合并块大小 |

## 6. 当前测试结果

标准环境当前结论（R11）：

| 项目 | 结果 |
| --- | --- |
| `mvn test` | 15 类 / 77 用例全部通过 |
| 接口扁平化测试 | 标准环境 R11 为 77 用例 / 77 通过；当前用例矩阵已更新到 135 条 |
| 已修复缺陷 | F-02/F-12 已关闭；导出超限、取消竞态和文件安全扫描均已补充回归 |
| 1M 导出 | 3/3 成功，平均约 23,317 行/s |
| 100k 导入 | 3/3 成功，平均约 4,511 行/s |
| 1M 导入 | 默认护栏下会被拦截；放开后在标准环境已成功，平均约 4,521 行/s |
| 数据一致性 | 测试后正式表行数可对账，暂存表残留为 0 |

## 7. 复现命令

```bash
# 接口扁平化测试
BASE_URL='<STANDARD_BASE_URL>' \
API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>' \
API_SECURITY_DEMO_ADMIN_TOKEN='<ADMIN_TOKEN>' \
python3 scripts/run_flat_tests.py

# 生成 10 万行导入文件
python3 scripts/gen_perf_import_file.py \
  --rows 100000 \
  --prefix STD100K \
  --out /tmp/perf_100k.xlsx

# 导出性能测试
BASE_URL='<STANDARD_BASE_URL>' API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>' \
python3 scripts/perf_bench.py --mode export --runs 3 --label exp-std

# 导入性能测试
BASE_URL='<STANDARD_BASE_URL>' API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>' \
python3 scripts/perf_bench.py --mode import --file /tmp/perf_100k.xlsx --runs 3 --label imp-std-100k
```

脚本生成的 `docs/test/live-test-results.json` 包含真实地址、重定向地址和响应片段，已加入 `.gitignore`，不提交到仓库。
