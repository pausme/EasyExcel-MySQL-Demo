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

### 5.5 300 万级导出矩阵（R25，2026-08-22，PERF-01 部分）

播种 3,000,003 行（服务端 ~11 分钟）后实测导出矩阵：

| 格式 | run | 状态 | 处理耗时(s) | 吞吐(行/s) |
| --- | ---: | --- | ---: | ---: |
| `CSV` | 1 | SUCCESS | 36.8 | **81,439** |
| `CSV` | 2 | SUCCESS | 35.9 | **83,553** |
| `ZIP_CSV_PARTS` | — | SUCCESS | 33.1 | **90,738** |
| `XLSX_SINGLE_SHEET` | — | **按设计失败**（>1,048,575，1.6s 快速失败+正确提示） | — | — |

- **CSV/ZIP 吞吐为 XLSX 方案（23.3k）的 3.5~3.9 倍**——大数据量导出首选 `ZIP_CSV_PARTS`（90.7k 行/s，3M 行仅 33s）。
- 交付验证：302 签名地址 → 完整 GET 200，ZIP 包 29.3MB（含 part-*.csv），下载 ~0.63MB/s（跨公网）。
- 服务器全程健康（可用内存 635MB、负载峰值 ~1.1）。
- 300 万导入未在本轮执行（护栏默认 20 万；1M 已在 R10 验证线性，300 万导入建议拆分任务或继续放开护栏后压测——留待 PERF-01 完整收官）。

### 5.6 PERF-01 完整矩阵：300 万 / 500 万（700 万实测）· R26，2026-08-22

#### 导出矩阵（可见数据 7,000,003 行 = 追加式 seed 累计）

| 场景 | run | 状态 | 处理耗时(s) | 吞吐(行/s) |
| --- | ---: | --- | ---: | ---: |
| CSV 7M | 1 | SUCCESS | 101.5 | 68,950 |
| CSV 7M | 2 | SUCCESS | 82.1 | **85,277** |
| ZIP_CSV_PARTS 7M | — | SUCCESS | 77.6 | **90,257** |
| 并发 2×7M（ZIP+CSV 同发） | 各 1 | 双 SUCCESS | 146.2 / 151.2 | 聚合 **92,700**（≈单跑吞吐，资源打满无排队失败） |

- **ZIP 吞吐 3M→7M 零衰减**（90,738→90,257）；导出池 2/2 双任务并发时聚合吞吐与单跑持平——磁盘已饱和，加线程无益。
- 并发时段叠加 100k 导入：SUCCESS 59.8s（1,672 行/s，较独占降速 2.4×，稳定完成）。

#### 300 万导入（护栏临时放开，测毕已恢复）

| 指标 | 实测 |
| --- | --- |
| 文件 | 96.7MB，300 万行全新学号（PERF3M 前缀），跨网上传 ~5 分钟 |
| 处理（started→finished） | **630.5s，4,757 行/s**（暂存 ~440s + 分块合并 ~190s） |
| 合并阶段进度 | completedCount 300 万（暂存完成）→ 回退 28 万起合并分块 → 300 万 SUCCESS |
| 服务器稳定性 | 全程健康：可用内存 529~590MB、swap ~1.0GB，**F-11 未复现**（swap + worker=6 + 分块合并三层缓解生效） |
| 1M→3M 线性度 | 4,521 → 4,757 行/s（+5%，接近线性） |

#### 索引与查询路径

- `student_record` 现有索引：PRIMARY(id)、uk(version,student_no)、idx(version,id)、idx(import_task_id)——覆盖导出游标分页 `WHERE import_version=? AND id>last ORDER BY id LIMIT n` 与导入清理 `WHERE import_task_id=?`。
- EXPLAIN：导出游标查询命中复合索引，`Using index`（覆盖索引，不回表）；优化器偶选 uk(version,student_no) 起步（rows 估算 20 万），实际 LIMIT 5000 游标推进稳定——7M 表全量导出 77~101s 无退化，索引方案有效。
- 注意点：追加式 seed 使当前版本含 700 万行、历史版本行物理保留（用户观察到的“700 万+”即含版本历史），`idx(version,id)` 保证导出只扫当前版本——版本清理任务裁剪历史后物理体积回落。

#### 线程池结论

- `EXPORT_CORE_POOL_SIZE=2`：双任务并发已打满磁盘（聚合=单跑），继续加线程只会增加内存与上下文切换，维持 2 为最优。
- `IMPORT_WORKER_COUNT=6`：3M 导入稳定 4,757 行/s，瓶颈为全持久化 fsync（§5.1），worker 增益已尽。

### 5.7 持久化配置 A/B 实测（R28，2026-08-24）

| 配置 | run | 处理耗时(s) | 吞吐(行/s) |
| --- | ---: | ---: | ---: |
| `trx_commit=1` + `sync_binlog=1`（全持久化，基线） | 1 | 25.74 | 3,885 |
| `trx_commit=2` + `sync_binlog=100`（relaxed） | 1 | 18.50 | **5,405** |
| `trx_commit=2` + `sync_binlog=100`（relaxed） | 2 | 20.93 | 4,778 |

- **实测提升 ~31%（均值 5,092 vs 3,885），最佳单次 +39%**——修正了此前"约 2 倍"的推断；fsync 只是瓶颈之一，解析、EasyExcel、网络转发同样占耗时。
- 测试后配置已恢复全持久化（`trx_commit=1`、`sync_binlog=1`）。
- 语义提醒：relaxed 下 OS 崩溃可能丢最后一秒事务（MySQL 进程崩溃不丢）；仅建议测试/可容忍环境使用。

## 6. 推荐配置

| 场景 | 推荐参数 | 理由 |
| --- | --- | --- |
| 标准环境单次导入（默认） | 默认护栏 `IMPORT_MAX_ROWS_PER_TASK=200000`（超限 400 拒绝），worker=6，swap ≥2GB | 100k 实测 4.5k 行/s（R10），护栏拦截高风险任务 |
| 标准环境百万级导入（≤300 万） | 放开 `IMPORT_MAX_ROWS_PER_TASK` 后可直接执行：1M 221s / 4,521 行/s（R10）、3M 630s / 4,757 行/s（R26），接近线性；保持 worker=6 与 swap | R10/R26 实证 |
| 标准环境导出（≤100 万） | 单任务单 Sheet，`EXPORT_CORE_POOL_SIZE=2` | ~23.3k 行/s，3/3 稳定 |
| 标准环境导出（>100 万） | **`format=ZIP_CSV_PARTS`**（或 CSV） | R25 实测 90.7k 行/s，300 万行 33s；XLSX 受单 Sheet 上限必然失败 |
| 本地 DB / 高配环境 | 导入 `IMPORT_WORKER_COUNT=16`、`HIKARI≥24` 可达 ~64k 行/s；默认 6 性价比更稳 | 本地基线矩阵 |
| 全持久化云盘调优 | 测试库可评估 `innodb_flush_log_at_trx_commit=2`、`sync_binlog=100` | **A/B 实测（R28）**：+31~39% 导入吞吐（见 §5.7），非此前推测的 2 倍；断电丢秒级数据 |

## 7. 暂存校验导入的额外成本（实测）

- R7 实测基于历史的"`student_import_stage` 暂存 → 校验 → 单事务合并 `student_record`"策略。
- 当前代码已演进为"`student_import_stage` 暂存 → 全量校验 → 分块事务合并 `student_record`"，用短事务降低百万级合并风险。
- R7 标准环境实测：播种直写 11,300 行/s vs 导入 3,908 行/s → 历史单事务暂存校验导入的固定成本约为直写的 2.9 倍耗时（暂存多写一次 + 校验扫描 + 最终合并）。
- 换来的保证：解析、暂存或最终校验失败时当前可见版本零污染；当前代码已引入 `import_version` 可见版本切换，构建新版本或发布失败不会影响旧版本。

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
