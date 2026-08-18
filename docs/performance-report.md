# 性能压测与调优报告

> 对应 `docs/project-roadmap-todo.md` 中已完成的「性能压测和调优报告」。本文固化导入/导出的压测方法、环境、参数与结论，便于复盘与可复现。
>
> **数据基线声明**：以标准测试环境（2026-08-14 ~ 08-17，R7/R10 性能轮）为权威数据源；更早的本机 WAN 轮与旧云端部署轮数据已作废。R11 文件安全扫描与清理治理见 [docs/test/测试执行记录.md](test/测试执行记录.md)。

## 1. 测试目标

形成可复现的导入/导出压测结论，说明不同参数（导入 worker 数、批次大小、Hikari 连接池、导出分页大小）对吞吐与稳定性的影响，并给出推荐配置与风险边界。

## 2. 测试机器与部署（标准环境）

| 项 | 值 |
| --- | --- |
| 服务器 | 标准测试服务器（详见 `<STANDARD_BASE_URL>` 对应主机），**1.6GB 内存，初期无 swap**（2026-08-17 已加 2GB swap） |
| 应用 | Docker 部署，`<STANDARD_BASE_URL>`（Spring Boot 2.6.13），开启 Bearer Token 鉴权 |
| MySQL | `<DB_HOST>:<DB_PORT>`，MySQL 8.0.32，与应用同机；`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`（全持久化），`max_allowed_packet=64MB` |
| Redis | `<REDIS_HOST>:<REDIS_PORT>`（任务状态缓存，同机） |
| MinIO | `<MINIO_ENDPOINT>`（桶 `student-excel`，同机） |
| 压测机 | macOS（跨公网发起 HTTP；数据面在服务器本地，吞吐按任务自身 `startedAt/finishedAt` 计算，不含上传时间） |

> **环境定性**：应用与全部中间件同机的单机 Docker 部署；磁盘为云盘，全持久化配置下每次事务提交双 fsync。**该硬件的瓶颈是云盘 fsync 与内存，而非网络**。

## 3. 参数维度与可调性

| 维度 | 环境变量 | 可环境化调整 | 标准环境取值 |
| --- | --- | --- | --- |
| 导入 worker 数 | `IMPORT_WORKER_COUNT` | ✅ | 16 |
| 导入并发任务数 | `IMPORT_MAX_CONCURRENT_TASKS` | ✅ | 1 |
| 导入队列容量 | `IMPORT_QUEUE_CAPACITY` | ✅ | 20 |
| Hikari 连接池 | `HIKARI_MAXIMUM_POOL_SIZE` | ✅ | 32 |
| 导入批次大小 | `app.excel.import-batch-size` | ✅（环境变量可配，代码内限幅 500~5000） | 2000 |
| 导出分页大小 | `app.excel.export-page-size` | ✅（环境变量可配，代码内限幅 1000~10000） | 5000 |
| 导出线程池 | `EXPORT_CORE_POOL_SIZE` / `MAX` | ✅ | 2 / 2 |
| 导入合并事务超时 | `IMPORT_TRANSACTION_TIMEOUT_SECONDS` | ✅ | 60 |

## 4. 本地 DB 历史基线（同代码库参考数据）

来源：项目 README 实测记录（本机 MySQL，2026-08-04~05），100 万行。

### 4.1 导入 worker 数矩阵

| worker | 耗时(ms) | 吞吐(行/s) |
| ---: | ---: | ---: |
| 4 | 42,718 | 23,400 |
| 6 | 33,021 | 30,283 |
| 8 | 34,265 | 29,184 |
| 16 | 15,671 | 63,812 |

### 4.2 导出（单 Sheet）

| 场景 | 耗时(ms) | 吞吐(行/s) |
| --- | ---: | ---: |
| 单任务单 Sheet | 63,806 | 15,672 |

解读：worker 4→16 吞吐 ~23k→~64k 行/s，但 8 略慢于 6，**worker 数收益非线性**；约束 `IMPORT_WORKER_COUNT × IMPORT_MAX_CONCURRENT_TASKS ≤ HIKARI_MAXIMUM_POOL_SIZE`。

## 5. 标准环境实测（R7/R10 权威）

### 5.1 服务端播种（INSERT 直写基线）

`POST /api/excel/seed/1000000`：**88.5 s，~11,300 行/s**——这是该云盘在全持久化配置下纯批量 INSERT 的吞吐上限，是后续所有导入数字的参照系。

### 5.2 导出（1,000,006 行，单 Sheet，3 次连跑）

| run | 状态 | 处理耗时(s) | 吞吐(行/s) |
| ---: | --- | ---: | ---: |
| 1 | SUCCESS | 39.81 | 25,118 |
| 2 | SUCCESS | 46.38 | 21,562 |
| 3 | SUCCESS | 42.97 | 23,270 |
| **均值** | 3/3 | **43.05** | **23,317** |

- 导出吞吐 ~23.3k 行/s，为本机历史基线（15.7k）的 1.5 倍；瓶颈在分页读 + EasyExcel 写 + 同盘 fsync（临时文件落盘）。
- wall ≈ 处理耗时（相差 <3s）：任务派发即时，无排队延迟。

### 5.3 导入（100,000 行/次，3 次）

| run | 状态 | 处理耗时(s) | 吞吐(行/s) | 验证点 |
| ---: | --- | ---: | ---: | --- |
| 1 | SUCCESS | 25.0 | 4,000 | 同文件重复导入 → upsert 生效（净增 100k 而非 200k） |
| 2 | SUCCESS | 26.0 | 3,846 | 同上 |
| 3 | SUCCESS | 25.8 | 3,878 | 新学号前缀 → 纯 INSERT 路径；加 swap 后执行 |
| **均值** | 3/3 | **25.6** | **~3,908** | R7 历史全流程（解析+暂存+单事务合并）远低于 60s 事务超时 |

- **导入吞吐 ~3.9k 行/s，仅为播种直写（11.3k）的 ~35%**：差额即"暂存表多写一次 + 逐批校验 + 最终合并"的暂存校验导入成本（详见 §7）。
- 与本机基线 16 worker（63.8k 行/s）相差 ~16 倍，完全由云盘全持久化 fsync 决定——**worker 数在该硬件上已不是第一瓶颈**。
- 数据对账：测试后 `student_record`=1,200,006，逐笔一致；`student_import_stage` 残留 0。

### 5.4 导入 1M：不可行（硬件限制，F-11）

| 项 | 实测 |
| --- | --- |
| 结果 | 暂存进行至 ~58,000 行时 Linux OOM 杀死 mysqld（`dmesg`：anon-rss 589MB），整机服务中断数小时 |
| 根因 | 1.6GB 内存 + 无 swap + 四服务同机 Docker + 16 worker 并发事务 fsync 风暴 |
| 复测 | 100k×3 前两轮成功后第三轮提交阶段整机再次僵死（应用/Redis/SSH 全挂），需控制台硬重启 |
| 缓解（已实施） | 2026-08-17 新增 2GB swap（fstab 持久化，`vm.swappiness=10`）；此后 100k 导入全程稳定（swap 兜底 ~130MB，负载峰值 1.0） |
| 1M 重测（R10，2026-08-17） | **成功**：护栏临时放开至 1,000,000 后，分块合并（`IMPORT_MERGE_CHUNK_SIZE=5000`、worker=6、swap 兜底）下 1M 导入 **SUCCESS，处理 221.2s / 4,521 行/s**，与 100k 基准（4,511 行/s）完全线性；全程可用内存 400~530MB、负载峰值 3.16，未复现 F-11 |

## 6. 推荐配置

| 场景 | 推荐参数 | 理由 |
| --- | --- | --- |
| 标准环境单次导入（默认） | 默认护栏 `IMPORT_MAX_ROWS_PER_TASK=200000`（超限 400 拒绝），worker=6，swap ≥2GB | 100k 实测 4.5k 行/s（R10），护栏拦截高风险任务 |
| 标准环境百万级导入 | 放开 `IMPORT_MAX_ROWS_PER_TASK` 后可直接执行：R10 实测 1M 成功（221s / 4,521 行/s），分块合并消除长事务；保持 worker=6 与 swap | R10 实证（F-09 已关闭、F-11 受控） |
| 标准环境导出 | 单任务单 Sheet，`EXPORT_CORE_POOL_SIZE=2` | ~23.3k 行/s，3/3 稳定 |
| 本地 DB / 高配环境 | 导入 `IMPORT_WORKER_COUNT=16`、`HIKARI≥24` 可达 ~64k 行/s；默认 6 性价比更稳 | 本地基线矩阵 |
| 全持久化云盘调优 | 测试库可评估 `innodb_flush_log_at_trx_commit=2`、`sync_binlog=100` | 以断电丢秒级数据换 ~2 倍写入吞吐 |

## 7. 暂存校验导入的额外成本（实测）

- R7 实测基于历史的"`student_import_stage` 暂存 → 校验 → 单事务合并 `student_record`"策略。
- 当前代码已演进为"`student_import_stage` 暂存 → 全量校验 → 分块事务合并 `student_record`"，用短事务降低百万级合并风险。
- R7 标准环境实测：播种直写 11,300 行/s vs 导入 3,908 行/s → 历史单事务暂存校验导入的固定成本约为直写的 2.9 倍耗时（暂存多写一次 + 校验扫描 + 最终合并）。
- 换来的保证：解析、暂存或最终校验失败时正式表零污染；进入分块合并阶段后的数据库异常不会自动回滚已提交分块，严格全量原子需要版本切换或影子表方案。

## 8. 风险和边界

- **F-11（已受控）**：历史两次整机打挂实证；现三层缓解（swap + 行数护栏默认 20 万 + worker=6）。R10 放开护栏的 1M 导入全程稳定，未复现。
- **F-09（已关闭）**：R10 实测分块合并下 1M 导入完整成功（221.2s），不再依赖单长事务；100k 较旧单事务方案提速 ~15%。
- **批次/分页配置化已补齐**：当前 `IMPORT_BATCH_SIZE`、`EXPORT_PAGE_SIZE` 可通过环境变量调整，并在代码内做范围保护；后续压测应记录每轮参数值。
- **worker 边际收益递减**：本地基线 6→8 提速不明显甚至回退；标准环境瓶颈在盘不在 worker。
- **恢复协调器收尸延迟**：崩溃任务 ~3.4h 才被标记 FAILED，心跳超时阈值需调短。
- **跨公网压测注意**：上传 31.8MB 需 ~5 分钟（上行 ~0.1MB/s）；本报告吞吐均按任务 `startedAt/finishedAt` 计算，不受影响。

## 9. 复现步骤与产物

```bash
# 生成导入文件（流式低内存；换 prefix 制造纯 INSERT 负载）
python3 scripts/gen_perf_import_file.py --rows 100000 --prefix STD100K --out /tmp/perf_100k.xlsx

# 导出基准（基于存量数据）
BASE_URL=<STANDARD_BASE_URL> API_SECURITY_DEMO_USER_TOKEN=<USER_TOKEN> \
  python3 scripts/perf_bench.py --mode export --runs 3 --label exp-std-1m

# 导入基准（100k 级；1M 需先解决内存与事务超时）
BASE_URL=<STANDARD_BASE_URL> API_SECURITY_DEMO_USER_TOKEN=<USER_TOKEN> \
  python3 scripts/perf_bench.py --mode import --file /tmp/perf_100k.xlsx --runs 3 --label imp-std-100k
```

产物：本报告、[scripts/perf_bench.py](../scripts/perf_bench.py)、[scripts/gen_perf_import_file.py](../scripts/gen_perf_import_file.py)、[scripts/import_load_test.py](../scripts/import_load_test.py)、接口测试执行记录 [docs/test/测试执行记录.md](test/测试执行记录.md)。
