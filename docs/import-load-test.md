# 导入压测说明

脚本位置：`scripts/import_load_test.py`。

该脚本使用 Python 标准库发起 multipart 上传，并按请求并发度统计成功率、总耗时和吞吐。对于异步导入接口，推荐优先使用 `scripts/perf_bench.py`，因为它会按任务自身 `startedAt/finishedAt` 计算后台处理耗时，更适合排除公网文件上传时间。

## 1. 测试原则

- 先测 1 万或 10 万行，确认机器稳定后再扩大数据量。
- 标准小规格单机 Docker 环境默认仍不建议直接跑 100 万行导入；当前护栏会拦截超 20 万行任务，在标准环境放开护栏后，R10/R26 已验证 1M（4,521 行/s）与 3M（4,757 行/s）导入成功。
- 每次只调整一个变量，例如 worker 数、连接池大小或导入并发任务数。
- 测试结果必须同时看应用日志、任务状态、MySQL 连接/锁等待、系统内存、swap 和磁盘 IO。
- 真实地址、Token、签名 URL 不写入文档和 Git，只通过环境变量传入。

## 2. 环境变量

```bash
export BASE_URL='<STANDARD_BASE_URL>'
export API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>'
```

应用侧关键配置：

```bash
export IMPORT_WORKER_COUNT='4'
export IMPORT_MAX_CONCURRENT_TASKS='1'
export IMPORT_QUEUE_CAPACITY='20'
export HIKARI_MAXIMUM_POOL_SIZE='10'
export IMPORT_TRANSACTION_TIMEOUT_SECONDS='60'
```

容量约束：

```text
IMPORT_WORKER_COUNT * IMPORT_MAX_CONCURRENT_TASKS <= HIKARI_MAXIMUM_POOL_SIZE
```

这个约束只保证导入 worker 不超过连接池上限。生产或准生产环境还要给普通查询、导出、健康检查和后台任务预留连接。

## 3. 生成压测文件

```bash
python3 scripts/gen_perf_import_file.py \
  --rows 100000 \
  --prefix STD100K \
  --out /tmp/perf_100k.xlsx
```

如果重复使用同一个 prefix，正式表会走 upsert 更新路径；如果更换 prefix，会走纯 INSERT 路径。两种场景都应单独记录。

## 4. 推荐压测命令

### 4.1 单任务后台耗时基准

```bash
BASE_URL='<STANDARD_BASE_URL>' API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>' \
python3 scripts/perf_bench.py \
  --mode import \
  --file /tmp/perf_100k.xlsx \
  --runs 3 \
  --label imp-std-100k
```

该脚本关注异步任务处理耗时，不把客户端跨公网上传时间算入导入处理吞吐。

### 4.2 请求并发矩阵

```bash
python3 scripts/import_load_test.py \
  --base-url '<STANDARD_BASE_URL>' \
  --file /tmp/perf_100k.xlsx \
  --matrix 1,2,4 \
  --requests 4 \
  --output ./import-load-matrix.json
```

默认 `IMPORT_MAX_CONCURRENT_TASKS=1` 时，多余并发请求会被业务拒绝，这是预期行为，不代表线程池故障。要测试多个导入任务同时执行，需要同时调大：

- `IMPORT_MAX_CONCURRENT_TASKS`
- `IMPORT_WORKER_COUNT`
- `HIKARI_MAXIMUM_POOL_SIZE`
- 机器内存和 MySQL 可用连接

## 5. 当前基线

标准环境当前基线（R26 终态）：

| 场景 | 数据量 | 结果 |
| --- | ---: | --- |
| 100k 导入，3 次 | 100,000 行/次 | 3/3 成功，平均约 22.42 s，约 4,511 行/s |
| 1M 导入 | 1,000,000 行 | 默认护栏下会被拦截；放开后在标准环境成功，平均约 4,521 行/s |

本地高配或本地 DB 历史参考：

| worker | 数据量 | 耗时 | 吞吐 |
| ---: | ---: | ---: | ---: |
| 4 | 1,000,000 行 | 42,718 ms | 约 23,400 行/s |
| 6 | 1,000,000 行 | 33,021 ms | 约 30,283 行/s |
| 8 | 1,000,000 行 | 34,265 ms | 约 29,184 行/s |
| 16 | 1,000,000 行 | 15,671 ms | 约 63,812 行/s |

结论：worker 数收益依赖硬件和数据库写入能力。标准小规格云盘环境瓶颈在内存、fsync 和长事务，不在 Java 线程数。

## 6. 建议记录项

| 类别 | 指标 |
| --- | --- |
| 应用任务 | taskId、状态、startedAt、finishedAt、imported、batchCount、失败原因 |
| JVM | 堆内存、Full GC、线程数、导入 worker 活跃数 |
| 数据库 | 活跃连接、锁等待、死锁、redo/binlog fsync、慢 SQL |
| 系统 | CPU、内存、swap、磁盘 IO、容器重启次数 |
| 对象存储 | 源文件上传耗时、对象大小、错误文件是否生成 |

## 7. 风险边界

- 10 万行是当前标准环境已验证的稳定导入级别。
- 100 万行导入默认会被容量护栏拦截；放开后仍需要确认合并事务超时、worker、Hikari 和机器内存/swap 的组合。
- 如果业务必须支持百万级单文件导入，当前应先确认 `IMPORT_MAX_ROWS_PER_TASK`、`IMPORT_MERGE_CHUNK_SIZE`、worker、Hikari 和机器内存/swap 的组合，再做标准环境压测，而不是单纯调大 worker。
