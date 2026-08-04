# 导入压测

脚本位置：`scripts/import_load_test.py`。

脚本使用 Python 标准库，通过流式 multipart 上传 Excel，不会为每个并发请求把整个文件完整读入内存。

## 准备

先准备一个真实的 `.xlsx` 文件，例如通过导出接口生成并下载 112000 条或 1000000 条数据的文件。
压测会重复上传同一个文件，因此数据库中的 `student_no` 会执行更新，不会无限新增数据。

确认应用已配置：

```bash
export IMPORT_WORKER_COUNT='4'
export IMPORT_MAX_CONCURRENT_TASKS='1'
export HIKARI_MAXIMUM_POOL_SIZE='10'
```

默认配置只允许一个导入任务。要测试多个导入任务并发，需要同时调整：

```bash
export IMPORT_MAX_CONCURRENT_TASKS='2'
export IMPORT_WORKER_COUNT='4'
export HIKARI_MAXIMUM_POOL_SIZE='10'
```

总导入 worker 数不能超过连接池大小，即 `IMPORT_WORKER_COUNT * IMPORT_MAX_CONCURRENT_TASKS <= HIKARI_MAXIMUM_POOL_SIZE`。

## 单级压测

Windows PowerShell：

```powershell
python .\scripts\import_load_test.py `
  --base-url http://127.0.0.1:18088 `
  --file .\student-112000.xlsx `
  --concurrency 1 `
  --requests 1 `
  --output .\import-load-result.json
```

Linux/macOS：

```bash
python3 scripts/import_load_test.py \
  --base-url http://127.0.0.1:18088 \
  --file ./student-112000.xlsx \
  --concurrency 1 \
  --requests 1 \
  --output ./import-load-result.json
```

## 并发矩阵

下面的命令依次测试 1、2、4 个并发，每个级别发送 4 个请求：

```bash
python3 scripts/import_load_test.py \
  --base-url http://127.0.0.1:18088 \
  --file ./student-112000.xlsx \
  --matrix 1,2,4 \
  --requests 4 \
  --output ./import-load-matrix.json
```

重点观察：

- `success`、`failed`：请求成功率；
- `elapsedSeconds`：该并发级别的总耗时；
- `rowsPerSecond`：导入吞吐量；
- 应用日志中的批次耗时、重试次数和 worker 数；
- MySQL 活跃连接数、锁等待、死锁、CPU 和磁盘 IO；
- Java 堆使用、Full GC 和应用线程数。

默认 `IMPORT_MAX_CONCURRENT_TASKS=1` 时，`--concurrency 4` 的额外请求会被业务拒绝，这是预期行为，不代表线程池故障。测试真正的多任务并发前，先确认连接池和数据库能够承受对应的 worker 数量。

## 建议记录

至少记录以下组合：

| 文件规模 | worker 数 | 导入并发任务数 | 关注点 |
| --- | ---: | ---: | --- |
| 112000 行 | 1 | 1 | 单线程基线 |
| 112000 行 | 2 | 1 | 多 worker 收益 |
| 112000 行 | 4 | 1 | 当前默认建议 |
| 112000 行 | 4 | 2 | 多导入任务竞争 |
| 1000000 行 | 4 | 1 | 长事务、内存和数据库压力 |

不要一开始直接把 worker 数调到很大。每次只调整一个变量，并在 MySQL、应用和机器监控数据稳定后再比较结果。
