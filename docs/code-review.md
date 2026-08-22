# 代码评审报告（方法级 · 含调用链）

> **处置状态（2026-08-22 15:40）**：A1/A3/A4/A9/A10/A11/A14/A16 已修复并通过单测（176/176）与标准环境回归（77/77）；评审过程中追加发现 **A17（file_reference 排序规则冲突导致文件删除 500，FILE-01 引入）**，已通过新增 **V12 迁移**（统一全库 utf8mb4_unicode_ci，与 39 处建表 DDL 声明一致）彻底修复并在标准环境验证。A2/A12/A15 记录在案。

> 评审人：Claude（代码评审角色） · 日期：2026-08-22
> 范围：以项目文档（README、TODO 交付标准、性能报告）为基准，逐方法审查核心业务链路及其调用链。
> 结论速览：**总体架构正确、实现质量高**——发现 **1 项中等级别、3 项中低/低中、若干低级与信息级问题**，无阻塞性缺陷；各 DONE 任务的交付标准与实现基本一致。

## 一、已审查链路与方法（正向确认）

| 链路 | 方法（调用链） | 核对结论 |
| --- | --- | --- |
| 导入提交 | `ExcelDemoController.importExcel` → `StudentImportTaskServiceImpl.submitImport` → MinIO 上传 → `taskCenterService.createTask` → `submitExecution` | 校验→上传源文件→建任务→失败回滚对象+补偿记录，链路完整 ✅ |
| 导入执行 | `executeImport` → `TaskExecutionGuard.tryStart`（Redis 锁 + `claimRunning` CAS）→ `studentService.importExcel`（staging → `validateImportStageBeforeMerge` → 分块合并 → `promoteImportVersion` CAS 发布）→ `markSuccess/markFailed` | 双重防重入正确；快照校验+游标推进保护+逐块取消检查均在位 ✅ |
| 失败分类 | `markValidationFailed` / `classifySystemFailure` / `retryableFailure` | VALIDATION_ERROR retryable=false、错误文件上传失败有补偿降级 ✅ |
| 取消传播 | `cancelTask`（状态机）→ 回调 `checkCanceled`（DB 查最新态）→ merge 循环抛 `TaskCanceledException` → 版本模式先清理未发布行再重抛 | 级联正确，REPLACE 模式取消后零残留 ✅ |
| 任务重试/恢复 | `prepareRetry`（状态门 + retryCount 上限）→ `retry/recover` → `submitExecution` | 与文档一致 ✅ |
| 导出引擎 | `ReportExportEngine.write/writeCsv/writeCsvParts`：快照计数 → 游标分页（`nextCursor <= lastCursor` 抛异常）→ 逐页取消 → 进度封顶 | 快照边界、CSV 转义（逗号/引号/换行）、ZIP 分片逻辑正确 ✅ |
| 文件上传幂等 | `withUploadOperationLock`（owner 级 Redis 锁 → 状态分派 `execute/onSuccess/onAborted` → finally 释放） | LCK-04 交付标准达成；重复 complete 回查 file_record 不重写库 ✅ |
| 幂等键 | `IdempotencyServiceImpl.execute`：查重 → PROCESSING 占位（唯一约束兜底并发）→ 执行 → SUCCESS/FAILED 回写；指纹冲突 409 | 与 LCK-02 一致 ✅（见 A14） |
| 补偿执行 | `CompensationAutoExecutor.executeDueCompensations`：全局锁 → `listDueForAutoExecute` → CAS `markRunning` → handler 分派 → 成功/退避（2^n 封顶 2^10）/终态 | 与 CON-05 及 R24 实测一致 ✅ |
| 安全 | `PasswordServiceImpl`（PBKDF2-SHA256 120k 迭代+随机盐+格式内嵌参数）；`JwtTokenServiceImpl`（HMAC + `MessageDigest.isEqual` 常量时间比较 + type/exp 校验） | 哈希方案规范；签名比较防时序攻击 ✅（见 A15/A16） |

## 二、问题清单（按严重度排序）

### A1【中】APPEND 导入模式无失败清理 —— ✅ 已处置（文档已明确非原子语义 + 失败消息明示部分数据可能已生效；完整回滚需行级备份，记为后续扩展）

- 位置：`StudentServiceImpl.appendImportStageToCurrentVersion`
- 链路：`applyImportStage(importMode=APPEND)` → 分块 `mergeImportStageRangeToCurrentStudent`（每块独立事务提交到**当前版本**）
- 问题：REPLACE 模式失败时 `deleteStudentRowsByImportTaskIdQuietly` 清理未发布版本行；**APPEND 模式无任何 try/catch 清理**——第 N 块失败时前 N-1 块已提交且**立即对当前版本可见**（部分数据污染），且无版本切换可回滚。
- 与文档冲突：README/性能报告 §7“解析、暂存或最终校验失败时当前可见版本零污染”；TODO IMP-01 要求“明确冲突处理规则”。
- 建议：① 文档明确 APPEND 为“尽力而为、非原子”（短期）；② 实现上为 APPEND 记录已提交块区间，失败时按 `importTaskId` 删除本任务已追加行（与 REPLACE 同款清理，可行因为行带 importTaskId 标签）。

### A14【中低】幂等 PROCESSING 记录无超时回收 —— ✅ 已修复（超 10 分钟 CAS 回收重执行，新增 tryReclaimStaleProcessing + 2 个单测）

- 位置：`IdempotencyServiceImpl.execute` / `handleExisting`
- 问题：应用在 `action.execute()` 执行中崩溃（或 kill -9），记录永久停留 `PROCESSING`；此后同键请求永远收到“幂等请求正在处理中，请稍后重试”，**无自愈路径**（无 TTL/无僵死检测）。
- 建议：PROCESSING 超过阈值（如 10 分钟）视为失败，允许同键重新执行（CAS 更新 `created_at` 抢占）。

### A16【低中】JWT 密钥无最小长度校验 —— ✅ 已修复（requireJwtSecret 强制 ≥32 字符 + 单测）

- 位置：`JwtTokenServiceImpl.parse`（仅 `StringUtils.hasText(jwtSecret)`）；`AuthServiceImpl` 登录仅检查缺失不检查长度
- 问题：1 字符密钥可正常工作，显著削弱 HMAC-SHA256 强度。
- 建议：启动时校验 `jwtSecret.length() >= 32`，不足即拒绝启动（与“缺少密钥 409”同款快速失败）。

### A4【低】失败分类依赖中文关键字匹配 —— ✅ 已修复（按异常类型沿因果链分类优先，文案匹配降为兜底）

- 位置：`StudentImportTaskServiceImpl.classifySystemFailure`（`message.contains("MinIO")/("超过限制")/("超时")`）
- 问题：异常文案一改分类即错；与 ERR-01“避免依赖模糊中文异常”精神相悖。
- 建议：按异常类型（如 MinIO SDK 异常类、SQLTimeoutException）或错误码分类，文案匹配仅作兜底。

### A10【低】CSV 导出未防公式注入 —— ✅ 已修复（=+-@ 开头值前置单引号 + 单测）

- 位置：`ReportExportEngine.escapeCsvValue`
- 问题：值以 `=`/`+`/`-`/`@` 开头时，用户用 Excel 打开导出文件会执行公式（CSV Injection）。
- 建议：对以这四个字符开头的值前置 `'`（或空格）。

### A11【低】进度封顶与文档不一致 —— ✅ 已修复（导出统一封顶 95%，与导入/README 一致）

- 位置：`ReportExportEngine.calculateProgressPercent`（`min(99,...)`）vs README“任务完成前进度最高到 95%”
- 建议：统一（改文档为 99% 或代码为 95%）。

### A9【低】执行锁 TTL 文档约束 —— ✅ README 环境变量段已补注释

- 位置：`TaskExecutionGuard`（TTL 默认 3600s）
- 问题：护栏放开后的超大数据导入可能超 1h，锁过期后虽有 CAS+CANCELED 级联兜底（分析确认无重复执行窗口），但应如 `IMPORT_WORKER_FINISH_WAIT_SECONDS` 一样在文档标注“TTL ≥ 预期最长任务”。

### A2【低】进程级崩溃遗留未发布版本行（已有缓解）

- 位置：`mergeImportStageAsNewVersion`（catch 才清理；kill -9 不走 catch）
- 缓解：未发布版本行不可见 + “导入历史版本清理”任务会裁剪旧版本——建议确认清理任务覆盖“未发布版本”而不仅是“已发布历史版本”。

### A12【信息】上传操作锁 TTL（≥60s）vs 超大文件 compose 耗时

- 二次 complete 有 SUCCESS 状态分派兜底，仅 MinIO compose 可能重复执行（幂等性依赖对象存储行为）；当前规模可接受。

### A15【信息】手写 JWT 与无状态刷新令牌

- 手写实现含常量时间比较（关键防护在位），可接受；但 refresh token 无撤销机制（登出/改密后旧 refresh 仍有效至过期）——demo 取舍，建议 TODO 化。

### A3【低】死代码 —— ✅ 已清理

- `mergeImportStageAsNewVersion` 中 `mergedRows = chunkEndRowNo` 赋值后仅赋值未读取（最后值未使用）。


### A17【中·评审过程发现】file_reference 排序规则冲突导致文件删除 500

- 位置：`FileRecordMapper` 删除语句（`file_record.file_id` 与 `file_reference.file_id` 关联比较）
- 根因：标准环境 `file_record` 等早期表为 `utf8mb4_0900_ai_ci`，FILE-01 的 V11 迁移显式 `utf8mb4_unicode_ci`——两表关联比较触发 `Illegal mix of collations`，删除接口 500。
- 处置：✅ 已彻底修复——新增 `V12__unify_table_collation.sql` 迁移（幂等游标转换所有非 unicode_ci 业务表），标准环境应用后 **14 张表全部统一 utf8mb4_unicode_ci**（与迁移/Mapper/建表脚本共 39 处声明一致），回归 77/77 通过。

## 三、评审结论

1. **正确性**：核心链路（版本切换原子性、CAS 防重入、游标推进、状态机、补偿退避）实现与文档语义一致，25 轮实测中行为吻合；唯一实质性偏差是 **A1（APPEND 模式）**。
2. **合理性**：分层清晰（controller/service/repository + common 组件复用），锁/幂等/补偿/限流统一组件化，符合 TODO 各组交付标准。
3. **建议处理顺序**：A1（补清理或改文档）→ A16（一行启动校验）→ A14（PROCESSING 超时）→ A4/A10/A11（小改）→ 其余记录在案。
