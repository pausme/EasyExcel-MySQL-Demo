# Changelog

本项目的关键变更按时间倒序记录。版本号遵循语义化版本（主版本.次版本.修订号）。

## [Unreleased]

### Added（2026-08-24）
- **AI 自然语言查询**（方向④）：`POST /api/ai/students/query`——自然语言 → JSON 过滤条件 → 复用学生分页链路；模型只输出白名单字段（杜绝 SQL 注入面），未启用/解析失败明确降级（503/502）。配置：`APP_AI_ENABLED`、`APP_AI_CHAT_ENDPOINT`（OpenAI 兼容）、`APP_AI_API_KEY`、`APP_AI_MODEL`
- **多实例分布式实弹验证**（方向①）：双实例共库部署验证任务 CAS 单执行/悬挂收殓/版本 CAS 并发导入/清理锁互斥；修复 A18（bootstrap 引导账号唯一键冲突致第二实例启动崩溃）
- **性能回归冒烟**（QA-04）：`scripts/perf_smoke.py` 导入+导出全链路基准，JSON 基线对比，偏离 >30% 告警
- `import_load_test.py` 支持 `--token`（兼容 Bearer 鉴权环境，默认读 `API_SECURITY_DEMO_USER_TOKEN`）

### Changed
- **MySQL 持久化 A/B 实测**（方向②）：`trx_commit=2 + sync_binlog=100` 实测导入吞吐 +31~39%（3,885 → 5,092 行/s 均值）——修正性能报告此前"约 2 倍"的推断（§5.7）
- 版本清理改为单次调度内循环批次 + 独立批次上限 5 万（QA-09：原 1,000 行/小时对千万级堆积不可收敛，实测两轮清空 1,190 万行）

### Fixed
- A18 多实例启动：`bootstrapAdminIfNecessary` 捕获 `DuplicateKeyException`（冲突视为已引导）
- A17 文件删除 500：V12 迁移统一全库 `utf8mb4_unicode_ci`
- A14 幂等 PROCESSING 僵死：超 10 分钟 CAS 回收重执行
- A16 JWT 密钥最小长度 32 字符启动校验
- QA-07 登出撤销 refresh token（Redis 黑名单 + `/api/auth/**` 拦截器放行）

### Test
- 回归套件 77 → **94 用例**（auth/students/precheck/errors/admin 全覆盖），标准环境 94/94
- 单元测试 176 → **182 用例**（含 AI 白名单解析 3 例）

## [1.0.0] – 2026-08-22

首个完整功能版本：26 项 TODO 全部交付。

- 异步导入导出（暂存校验 + 分块合并 + 版本切换、XLSX/CSV/ZIP 多格式）
- 统一异步任务中心（心跳/恢复/失败分类/事件日志）
- 文件中心（直传/分片/秒传/断点续传/配额/安全扫描）
- 分布式锁与幂等（统一锁组件、Idempotency-Key、任务 CAS 防重入、上传完成幂等）
- 补偿体系（对账/任务补偿/管理端重放/自动执行器）
- JWT 认证与角色权限（AUTH-01/02）
- 可观测性（Micrometer 指标、TraceId 贯穿、下载审计、Prometheus）
- CI 门禁（单测 + Flyway 空库冒烟 + 周期集成）
- PERF-01 压测矩阵：3M/7M 导出（ZIP 90k 行/s）、3M 导入（4,757 行/s）、并发与索引结论
