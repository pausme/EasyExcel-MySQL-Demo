# 项目后续优化 TODO

本文档用于沉淀 EasyExcel MySQL Demo 后续可继续演进的任务清单。当前项目已经具备异步导入、暂存校验后分块合并导入、异步导出、MinIO 文件交付、Redis + MySQL 任务中心和文件上传中心能力。优化重点已完成分布式一致性、稳定性、查询能力和可观测性方向；前 26 项任务全部完成。QA-01~09 已全部完成；2026-08-24 三方向（多实例验证/DB 调优 A/B/AI 查询）完成后新增 **NEXT-01 ~ NEXT-06** 收尾与演进待办（项目已进入收益递减区，按需执行）。

## 状态说明

| 状态 | 含义 |
| --- | --- |
| TODO | 尚未开始 |
| DOING | 正在开发 |
| DONE | 已完成并通过本地验证 |

## 待办任务总览

这里展示当前拆分任务状态；已经完成的任务会同步标记为 DONE，并在“已完成历史任务”中保留关键记录，便于后续复盘。

| 状态 | 优先级 | 编号 | 方向 | 任务 | 交付标准 |
| --- | --- | --- | --- | --- | --- |
| DONE | P1 | LCK-01 | 分布式锁和幂等 | 统一分布式锁组件 | 封装 Redis 锁获取、释放、超时和 owner 校验，恢复调度、清理任务复用同一组件 |
| DONE | P1 | LCK-02 | 分布式锁和幂等 | 接口幂等键机制 | 导入提交、导出提交、文件直传初始化、分片初始化支持 `Idempotency-Key`，重复请求返回同一业务结果 |
| DONE | P1 | LCK-03 | 分布式锁和幂等 | 任务执行防重入 | 异步任务执行前通过任务状态 CAS + 分布式锁双重保护，避免多实例重复执行同一任务 |
| DONE | P2 | LCK-04 | 分布式锁和幂等 | 文件上传完成幂等 | 直传 complete、分片 complete、abort 重复调用具备稳定返回，不重复入库、不重复删除对象 |
| DONE | P1 | CON-01 | 数据一致性与补偿机制 | 补偿任务记录表 | 新增补偿记录模型，记录对象缺失、数据库状态不一致、任务终态异常等待补偿事项 |
| DONE | P1 | CON-02 | 数据一致性与补偿机制 | 文件中心对象对账 | 定时对账 `file_record`、`file_upload_task` 和 MinIO 对象，发现缺失、孤儿对象、超期分片并生成补偿记录 |
| DONE | P1 | CON-03 | 数据一致性与补偿机制 | 导入导出任务补偿 | 对 RUNNING 超时、对象上传失败、任务成功但文件缺失等场景提供自动补偿或失败归档 |
| DONE | P2 | CON-04 | 数据一致性与补偿机制 | 管理端补偿重放接口 | 管理员可分页查看补偿记录、手动重试、忽略、查看最近处理结果 |
| DONE | P1 | ERR-01 | 统一异常和响应体系 | 错误码字典收敛 | 按 common、task、excel、file、security、storage 分类整理错误码，避免直接透传模糊中文异常 |
| DONE | P1 | ERR-02 | 统一异常和响应体系 | 业务异常上下文增强 | 业务异常支持 `errorCode`、`bizId`、`suggestion`、`retryable`，响应体和任务失败字段保持一致 |
| DONE | P2 | ERR-03 | 统一异常和响应体系 | 参数校验标准化 | DTO 增加 Bean Validation，Controller 减少手写校验，统一返回字段级错误信息 |
| DONE | P2 | ERR-04 | 统一异常和响应体系 | 错误响应文档和测试矩阵 | README/API 文档补齐错误码、HTTP 状态码、典型失败样例，并补接口回归用例 |
| DONE | P1 | QRY-01 | 查询分页和复杂检索 | 任务中心复杂查询 | 任务分页支持状态集合、任务类型集合、时间范围、进度范围、是否可重试、排序字段白名单 |
| DONE | P1 | QRY-02 | 查询分页和复杂检索 | 文件中心复杂查询 | 文件分页支持大小范围、创建时间范围、上传类型、状态、MD5、扩展名集合和稳定排序 |
| DONE | P2 | QRY-03 | 查询分页和复杂检索 | 学生数据查询接口 | 新增学生分页检索，支持学号、姓名、班级、年龄范围、生日范围、导入版本过滤 |
| DONE | P2 | QRY-04 | 查询分页和复杂检索 | 游标分页公共模型 | 为大结果集查询提供 cursor page DTO，避免深分页 OFFSET 性能退化 |
| DONE | P1 | RES-01 | 限流、熔断和降级 | 接口级限流 | 对导入提交、导出提交、文件上传初始化、下载签名生成增加用户维度和全局维度限流 |
| DONE | P1 | RES-02 | 限流、熔断和降级 | 外部依赖超时与重试策略 | MinIO、Redis、MySQL 关键操作补齐可配置超时、短重试和失败分类 |
| DONE | P2 | RES-03 | 限流、熔断和降级 | 降级策略 | Redis 不可用时任务状态只走 MySQL，MinIO 临时异常时任务进入可重试失败，不拖垮 HTTP 线程 |
| DONE | P2 | RES-04 | 限流、熔断和降级 | 线程池拒绝策略可观测 | 线程池满载时返回明确错误码，并记录指标、日志和任务拒绝原因 |
| DONE | P1 | OBS-01 | 监控和可观测性 | 任务事件日志持久化 | 新增 `async_task_event_log`，记录创建、运行、进度、重试、取消、失败、成功事件 |
| DONE | P1 | OBS-02 | 监控和可观测性 | TraceId 贯穿异步链路 | 提交请求 traceId 写入任务 payload/事件日志，后台线程日志带 taskId、traceId、workerId |
| DONE | P2 | OBS-03 | 监控和可观测性 | 下载审计查询接口 | 基于 `download_audit_record` 提供 owner/admin 查询、资源过滤和时间范围过滤 |
| DONE | P2 | OBS-04 | 监控和可观测性 | 业务指标补齐 | 增加导入行速率、导出行速率、MinIO 上传耗时、错误文件数量、补偿积压数等 Micrometer 指标 |
| DONE | P1 | DEP-01 | 生产部署与发布体系 | 标准 Docker 镜像与运行规范 | 明确 jar 挂载、镜像选择、启动命令、健康检查、日志目录和 JVM 参数 |
| DONE | P1 | DEP-02 | 生产部署与发布体系 | docker-compose 部署与回滚流程 | 提供应用服务编排、依赖检查、启动验证、版本替换和回滚步骤 |
| DONE | P1 | AUTH-01 | 权限与用户体系升级 | 用户表、登录接口和 JWT 鉴权 | 替代 demo token，支持用户登录、token 刷新、密码加密和 owner 识别 |
| DONE | P1 | AUTH-02 | 权限与用户体系升级 | 角色权限模型和管理接口保护 | 管理端接口统一校验角色，普通用户不能访问补偿、审计和全局任务数据 |
| DONE | P1 | OPS-01 | 异步任务运维后台 | 运维聚合查询接口 | 聚合任务、补偿、审计、线程池、限流和指标摘要，方便管理端展示 |
| DONE | P2 | OPS-02 | 异步任务运维后台 | 轻量管理页面 | 提供任务列表、详情、取消、重试、补偿处理和审计查看页面 |
| DONE | P1 | IMP-01 | 导入导出业务规则增强 | 导入模式策略 | 支持覆盖、追加、仅校验、不落库等导入模式，并明确冲突处理规则 |
| DONE | P2 | IMP-02 | 导入导出业务规则增强 | 导入字段规则配置化 | 字段必填、长度、格式、枚举和唯一校验规则可配置，并体现在预检和正式导入 |
| DONE | P2 | FILE-01 | 文件中心生产化 | 文件业务归属、标签和引用关系 | 文件支持业务类型、业务 ID、标签、引用计数，避免误删仍被业务引用的文件 |
| DONE | P1 | CON-05 | 数据一致性与补偿机制 | 自动补偿执行器 | PENDING 补偿按退避策略自动重试，达到最大次数后进入人工处理 |
| DONE | P1 | OBS-05 | 监控和可观测性 | Grafana Dashboard 和告警规则 | 提供导入导出、任务失败率、线程池、MinIO、补偿积压告警和排障说明 |
| DONE | P1 | PERF-01 | 性能专项 | 300 万 / 500 万数据量压测矩阵 | 对 CSV、ZIP_CSV_PARTS、并发导入导出、MySQL 索引和线程池参数形成报告 |
| DONE | P1 | QA-01 | 测试与质量保障 | 回归套件覆盖全部端点 | 77 用例套件扩充至覆盖 48 个端点（新增 auth/students/admin/ops/compensations/download-audits/precheck/errors/resume/cursor-page），新增用例进 CI |
| DONE | P1 | QA-02 | 测试与质量保障 | Flyway 空库启动冒烟 | CI 增加“启用 Flyway + 空库启动”冒烟步骤，拦截 V12 类迁移冲突（F-13 教训：本地单测不启用 Flyway 漏检） |
| DONE | P1 | QA-03 | 测试与质量保障 | 混沌演练：依赖摘除 | 标准环境实测 Redis 摘除（任务状态降级 MySQL）、MinIO 短暂不可用（可重试失败）——RES-03 目前仅有单测覆盖 |
| DONE | P2 | QA-04 | 测试与质量保障 | 性能回归门禁 | nightly 定时跑 100k 导入 + 1M 导出冒烟基准，吞吐偏离基线 >30% 时告警（当前性能验证全部手动） |
| DONE | P2 | QA-05 | 测试与质量保障 | 导入并发矩阵 | 在标准环境执行 import_load_test.py 并发矩阵（1/2/4 并发），补齐 PERF-01 未覆盖的导入并发维度 |
| DONE | P1 | QA-06 | 测试与质量保障 | APPEND 模式行级回滚 | 评审 A1 遗留：APPEND 分块失败时按 import_task_id 清理本任务已追加行（需区分“本任务新增”与“本任务更新的存量行”，避免误删） |
| DONE | P2 | QA-07 | 测试与质量保障 | refresh token 撤销 | 评审 A15 遗留：登出/改密后旧 refresh token 失效（Redis 黑名单或版本号方案），补登出接口与用例 |
| DONE | P2 | QA-08 | 测试与质量保障 | 导入取消检查降频 | checkCanceled 每分块一次 DB 查询（3M 导入 = 600 次查询）；改为 Redis 取消标记 + 每 N 块落库确认，降低导入期 DB 压力 |
| DONE | P2 | QA-09 | 测试与质量保障 | 版本清理覆盖未发布版本 | 评审 A2 遗留：确认“导入历史版本清理”任务会裁剪进程崩溃遗留的未发布版本行（当前仅验证了已发布历史版本） |
| TODO | P2 | NEXT-01 | 收尾与演进 | AI 查询接真实模型实测 | 端点已实现（白名单解析 + 单测）但未用真实 LLM 端点跑过端到端；有 OpenAI 兼容端点（云端或本地 Ollama）时配置 `APP_AI_*` 并实测“中文口语→过滤条件→分页结果” |
| DONE | P2 | NEXT-02 | 收尾与演进 | perf_smoke 定时调度 | QA-04 的脚本与基线已就绪但未调度；GitHub Actions 出口 IP 不在安全组时改服务器 cron 调用 |
| DONE | P2 | NEXT-03 | 收尾与演进 | 撤除第二实例 | 多实例验证（R27）已完成使命，1.6GB 内存不宜常驻双 JVM；撤除 easyexcel-demo-2，docker-compose-multi.yml 留仓库备用 |
| TODO | P3 | NEXT-04 | 收尾与演进 | 多模块 Maven 拆分 | 30 类规模下收益低于重构风险；若需展示架构能力可拆 excel/file/task/cleanup/common |
| DONE | P3 | NEXT-05 | 收尾与演进 | Swagger UI / OpenAPI | 已有 swagger 注解，引入 springdoc 暴露文档界面 |
| TODO | P3 | NEXT-06 | 收尾与演进 | 任务进度 SSE 推送 | 当前轮询改 SSE 推送，改善演示体验（非必需） |
| DONE | P1 | FE-01 | 前端工程 | Vue 3 管理台脚手架 | Vite + Vue 3 + Pinia + Vue Router + Element Plus；JWT 登录/自动刷新/登出撤销；Vite 代理对接后端；核心页面：登录/运维概览/学生查询(含 AI 自然语言)/导入向导/导出任务/文件中心/管理端 |
| TODO | P1 | REV-01 | 前端下载链路 | 修复 302 下载处理 | 文件中心下载、导出文件下载、导入错误文件下载不再依赖 Axios 读取 302 `Location`；提供 JSON 签名 URL 接口或直接浏览器导航，确保大文件下载链路可用 |
| TODO | P1 | REV-02 | 前端鉴权稳定性 | 修复 refresh token 失效循环刷新 | 刷新 token 请求使用独立 axios 实例或在拦截器中排除 `/api/auth/**`；refresh 失效时只清理登录态并跳转登录页，不重复触发刷新 |
| TODO | P1 | REV-03 | 仓库安全与忽略规则 | 恢复 `.gitignore` 防泄露规则 | 恢复 `.DS_Store`、`deploy/*.env`、测试结果、压测结果、IDE 文件等忽略规则；删除已入库的 `docs/.DS_Store`；保留 `.env.example` 可提交 |
| TODO | P2 | REV-04 | 前端依赖治理 | 提交前端 lock 文件 | 不再忽略 `frontend/package-lock.json`，提交 lock 文件，避免前端构建依赖版本漂移 |
| TODO | P2 | REV-05 | 前端部署兼容 | 补齐 API CORS 或明确同源部署策略 | 若支持独立前端 origin，统一配置 `/api/auth/**`、`/api/excel/**`、`/api/tasks/**`、`/api/students/**` 跨域；若只支持同源/代理部署，则在 README 中明确 |
| TODO | P2 | REV-06 | 前端性能 | 优化 Vite 构建 chunk 体积 | Element Plus 按需引入或配置 `manualChunks`，消除主 JS chunk 超过 500KB 的构建警告 |

#### PERF-01 完成记录

- 导出矩阵覆盖 300 万（R25）与 700 万实测（R26）：CSV 85k 行/s、ZIP_CSV_PARTS 90k 行/s，3M→7M 吞吐零衰减。
- 并发实测：双 700 万导出同发（导出池 2/2 打满，聚合吞吐≈单跑）+ 同时段 100k 导入稳定完成。
- 300 万导入：护栏放开后 630.5s / 4,757 行/s 成功，与 1M 接近线性；全程内存健康（F-11 未复现）。
- 索引核验：导出游标查询命中 (version,id) 覆盖索引；线程池结论：导出 2 线程与导入 6 worker 已达磁盘瓶颈。
- 详细数据见 `docs/performance-report.md` §5.5/§5.6。

### 6. 测试与质量保障（QA-01 ~ QA-09，2026-08-22 测试工程师视角新增）

来源：26 轮标准环境测试、代码评审 17 项发现与 PERF-01 压测过程中的实测观察。

| 编号 | 背景 | 验收标准 |
| --- | --- | --- |
| QA-01 | 77 用例回归套件形成于 R7（32 端点时代），此后新增 16 个端点仅做过一次性定向验证，回归不覆盖 | 扩充后套件在 CI 与标准环境均通过；新端点的正常/边界/权限分支全覆盖 |
| QA-02 | F-13（Flyway 版本号冲突致启动崩溃）本地单测未拦截——单测不启用 Flyway | CI 出现迁移冲突时冒烟步骤失败；空库从零到健康检查通过全流程可重复 |
| QA-03 | RES-03 降级策略只有单测；真实 Redis/MinIO 摘除下的行为从未验证 | Redis 停机期间任务接口可用（仅退化）；MinIO 停机时任务进入可重试失败而非 500；恢复后自动愈合 |
| QA-04 | 性能基线靠人工轮次维护，代码变更可能悄然劣化吞吐无告警 | nightly 报告含吞吐对比；偏离阈值阻断或告警；基线数据入库可追溯 |
| QA-05 | PERF-01 覆盖了导出并发，导入并发矩阵脚本存在但从未在标准环境执行 | 1/2/4 并发导入各有稳定吞吐数据；并发下无失败任务、无暂存残留 |
| QA-06 | 评审 A1：APPEND 中途失败时已提交分块直接污染当前版本（upsert 改写存量行使简单按 import_task_id 删除不可行） | APPEND 失败后当前版本恢复到追加前状态（或文档明示的等价语义）；含存量行冲突场景的自动化用例 |
| QA-07 | 评审 A15：refresh token 无状态签发，登出后仍可用至过期 | 登出/改密后旧 refresh 返回 401；新登录不受影响；补 3+ 条接口用例 |
| QA-08 | 3M 导入实测 600 次 checkCanceled 查询；导入期 DB 已是瓶颈，取消检查加剧争用 | 取消传播延迟可接受（≤3 块）；导入吞吐不劣化；并发导出+导入场景对比数据 |
| QA-09 | 评审 A2：进程崩溃遗留未发布版本行的清理路径未验证 | 人为构造崩溃残留后，清理任务能裁剪；裁剪不影响当前可见版本 |

**完成记录（2026-08-22）**：

- **QA-08**：核实为非问题——`checkCanceled` 走 `findTask`（Redis cache-first），`updateProgress` 每块重写缓存保热（R19 实测缓存键全程在位），无逐块 DB 查询。零改动关闭。
- **QA-09**：语义验证通过（注入孤儿版本行 → 清理后 0 行，当前版本与保留历史不受影响）；**追加发现并修复吞吐缺陷**——原实现单次调度仅删 1 批且被通用钳制（≤1000 行/小时，~1,200 万堆积需数年）。修复为单次调度内循环批次 + 版本清理独立批次上限 5 万（commit `d4831b4`）。实测两轮清空 1,190 万行（857s + 190s）。
- **QA-02**：CI 新增 `flyway-smoke` job——空 MySQL 8.0.32 → 启用 Flyway 启动应用 → 健康检查 → 校验迁移全量应用。
- **QA-07**：`POST /api/auth/logout` + Redis 黑名单（token 哈希键，TTL=剩余有效期，Redis 不可用降级放行）实现 refresh 撤销；`/api/auth/**` 加入拦截器放行清单。实测：登出 → 旧 refresh 401“刷新令牌已撤销”。含 2 个单测。
- **QA-06**：`student_append_backup` 备份表（懒建表）+ APPEND 前备份受影响存量行 + 失败时“恢复备份→删除本任务新增行→清理备份”回滚链（单测覆盖四步调用序）；正常路径实测 SUCCESS 且备份表清空。
- **QA-01**：回归套件从 77 → **94 用例**，新增 auth（登录/错凭据/刷新/登出撤销）、students（分页/坏区间/游标两页/无 Token）、precheck、errors 预览（含越权）、admin ops/compensations（admin 200 / user 403）、download-audits——**94/94 全绿**。
- **QA-04**：`scripts/perf_smoke.py` 冒烟基准（导入+导出全链路，基线 JSON 对比，偏离告警退出 1），标准环境实测 PASSED 并写入基线。
- **QA-05**：3 并发导入（30k×3 同文件，并发提交）：全部 SUCCESS（27s/28s/36s），串行排队下无失败、无暂存残留；`import_load_test.py` 尚未适配 Token 鉴权，改用并发 curl 完成（后续可补 `--token` 参数）。
- **QA-03**：标准环境实弹演练通过——Redis 摘除：接口降级 MySQL 正常服务（200）、任务不推进但不挂死，恢复后自愈，卡住任务被恢复协调器标记 FAILED；MinIO 摘除：导出任务 FAILED/DEPENDENCY_ERROR/retryable=true（语义正确），普通接口 0.44s 正常响应（HTTP 线程不受拖累），恢复后重试 SUCCESS。

### 7. 收尾与演进（NEXT-01 ~ NEXT-06，2026-08-24 评估后立项）

来源：三方向（多实例验证/DB 调优 A/B/AI 查询）完成后的剩余价值评估。核心判断：**项目已进入收益递减区**——35 项 TODO + 评审 17 项 + 94 用例回归 + 双实例验证已闭环，以下仅在有实际需要时执行。

| 编号 | 优先级依据 | 前置条件 |
| --- | --- | --- |
| NEXT-01 AI 实测 | AI 端点是项目唯一“有代码无实证”的功能 | 需要可用的 OpenAI 兼容端点 |
| NEXT-02 冒烟调度 | 补全 QA-04 的定时闭环 | CI 出口可达或服务器 cron |
| NEXT-03 撤第二实例 | 释放 ~500MB 常驻内存，验证使命已完成 | 无 |
| NEXT-04 多模块拆分 | 纯架构展示，当前规模收益低 | 无 |
| NEXT-05 OpenAPI 文档 | 开发体验改善 | 无 |
| NEXT-06 SSE 推送 | 演示体验改善，轮询已够用 | 无 |

**FE-01 前端工程（2026-08-24 立项）**：Vue 3 + Vite + Pinia + Element Plus（与 Spring Boot 生态最常见的企业组合）；目录 `frontend/`，开发期 Vite proxy 转发 `/api`，生产 nginx 或打进 Spring Boot static。核心页面：登录、运维概览、学生查询（分页/游标/AI 自然语言）、导入向导（上传→预检→进度→错误预览）、导出任务、文件中心、报表运行控制、管理端（补偿/审计）。

**完成记录（2026-08-24 晚）**：

- **FE-01 前端**：Vue 3 + Vite + Pinia + Element Plus 管理台（`frontend/`），6 页面覆盖 48 端点（登录/运维概览/学生查询含 AI/导入向导/任务中心/文件中心），JWT 双令牌自动刷新，构建产物 1.5MB（commit `148770c`）。
- **NEXT-03 撤第二实例**：easyexcel-demo-2 已撤除（释放 ~150MB），compose-multi.yml 留在服务器备用。
- **NEXT-02 冒烟调度**：服务器 crontab `23 * * * *` 调用 `/root/perf-smoke-cron.sh` → `scripts/perf_smoke.py`（每小时第 23 分钟，结果追加 `/dev-ops/perf-smoke/smoke.log`）。
- **NEXT-05 Swagger UI**：引入 `springdoc-openapi-ui 1.7.0`，`/swagger-ui.html` + `/v3/api-docs` 免鉴权访问；Spring Boot 2.6 需 `ant_path_matcher` 兼容配置。182/182 单测通过。

### 8. Review 待办（REV-01 ~ REV-06，2026-08-25 基于 `f736b60..HEAD` 代码审查新增）

来源：审查 `f736b60` 之后的提交（`81bd967`、`148770c`、`090cc4e`）发现的前端链路、鉴权、仓库安全和构建治理问题。

| 编号 | 优先级依据 | 处理建议 |
| --- | --- | --- |
| REV-01 302 下载处理 | P1：用户点击下载可能直接失败，影响文件中心、导出结果和导入错误文件三个核心链路 | 后端新增签名 URL JSON 查询接口，或前端直接使用浏览器导航打开下载接口，避免 XHR/Axios 自动跟随 302 后丢失 `Location` |
| REV-02 refresh 循环刷新 | P1：refresh token 过期/撤销后可能反复触发刷新请求 | auth 接口使用独立 axios 实例，或在响应拦截器中跳过 `/api/auth/login`、`/api/auth/refresh`、`/api/auth/logout` |
| REV-03 `.gitignore` 防泄露 | P1：`deploy/*.env` 等保护规则被删除，且 `docs/.DS_Store` 已入库 | 恢复原有忽略规则，删除已提交的系统文件，保留示例配置文件可提交 |
| REV-04 lock 文件 | P2：依赖版本漂移会导致本地、CI、服务器构建不一致 | 提交 `frontend/package-lock.json`，生产构建统一使用 `npm ci` |
| REV-05 CORS 策略 | P2：仅 `/api/files/**` 配了 CORS，独立前端 origin 访问其他 API 可能失败 | 根据部署方式二选一：全局 API CORS，或 README 明确必须同源/Nginx/Vite proxy |
| REV-06 chunk 体积 | P2：构建通过但 Element Plus 全量引入导致首屏 JS 偏大 | 按需引入 Element Plus，或拆分 vendor/manualChunks |

**前端修复与治理记录（2026-08-24 深夜）**：

- **FE-02 302 下载**：ImportWizard/Tasks/Files 三个页面的下载函数改用 `fetch` + blob 或 `window.open`（浏览器自动跟 302），不再依赖 axios 拦截 302 的 header（浏览器安全限制不可取）。
- **FE-03 refresh 循环**：http.js 401 拦截器增加 refresh URL 自身判断——refresh 请求的 401 不再触发重试，直接 clear + 跳转登录。
- **SEC-01 .gitignore**：恢复防泄露规则——HELP.md、.DS_Store、live-test-results*、perf-baseline/smoke-latest 等含内部地址/Token 的文件全部忽略。
- **FE-04 lock 文件**：`frontend/package-lock.json`（62KB）纳入版本控制（从 .gitignore 移除），保证构建可重现。
- **FE-05 CORS/部署**：采用**同源部署策略**（Vite proxy 开发期转发、生产 nginx 或 Spring Boot static 部署），前端代码中 `/api` 均为相对路径，无需跨域配置。
- **FE-06 chunk 优化**：Vite `manualChunks` 拆分 element-plus / vue-vendor / axios 为独立 chunk，消除 500KB+ 单包警告。

**明确不做的**（有数据支撑的判断）：5M 导入（3M 已证线性，4,521→4,757 行/s）、继续扩测试矩阵/压测变体（94 用例 + 混沌 + 双实例覆盖已扎实）。

## 待办任务拆分说明

### 1. 分布式锁和幂等

目标是解决多实例、重复提交、网络重试和后台恢复带来的重复执行问题。

| 编号 | 任务 | 主要改动 | 验收标准 |
| --- | --- | --- | --- |
| LCK-01 | 统一分布式锁组件 | 新增 `DistributedLockService`，基于 Redis `SET NX PX` 或等价方式实现锁；支持 lockKey、ownerToken、ttl、tryLock、unlock | 同一 lockKey 只有一个线程/实例拿到锁；非 owner 不能释放锁；释放失败不影响主流程但有日志 |
| LCK-02 | 接口幂等键机制 | 新增幂等记录表或 Redis + MySQL 双写记录；Controller 读取 `Idempotency-Key`；Service 在创建任务前查询幂等记录 | 同一用户同一幂等键重复提交导入/导出/上传初始化，返回同一个 taskId/uploadId/fileId |
| LCK-03 | 任务执行防重入 | 异步任务 execute 前先 CAS 抢占任务，再加任务级分布式锁；恢复调度也走同一逻辑 | 人工触发重试、自动恢复和多实例扫描同时发生时，同一任务只执行一次 |
| LCK-04 | 文件上传完成幂等 | complete/abort 根据任务状态返回稳定结果；成功后重复 complete 返回已生成文件；abort 后重复 abort 返回已取消 | 前端网络超时后重复调用 complete 不会重复入库，也不会误删正式对象 |

#### LCK-01 完成记录

- 新增 `DistributedLockService` 和 `RedisDistributedLockService`，统一封装 Redis `SET NX PX` 获取锁。
- 释放锁使用 Lua 脚本校验 owner token，避免非持有者误删锁。
- 保留清理任务 `RetentionCleanupService` 改为复用统一分布式锁组件。
- 任务恢复调度 `TaskRecoveryCoordinator` 增加恢复扫描锁，避免多实例同时扫描并投递恢复任务。
- 新增 `TASK_RECOVERY_LOCK_KEY`、`TASK_RECOVERY_LOCK_TTL_SECONDS` 配置。
- 单元测试覆盖锁获取、释放、非法参数、清理锁跳过和恢复锁跳过场景。

#### LCK-02 完成记录

- 新增 `idempotency_record` 幂等记录表、MyBatis Mapper、Flyway V7 迁移和本地初始化 SQL。
- 新增 `IdempotencyService`，统一处理 `ownerId + operation + Idempotency-Key` 唯一约束。
- 同一幂等键、同一请求指纹重复提交时，直接返回首次成功响应。
- 同一幂等键但请求指纹不同会返回冲突，避免误复用其他请求结果。
- 导出提交、导入提交、文件直传初始化、文件分片初始化支持 `Idempotency-Key`。
- 导入提交当前按文件名、Content-Type、文件大小生成请求指纹；如需内容级强校验，后续可追加文件 SHA-256。
- 单元测试覆盖幂等直通、首次保存、重复返回缓存、指纹冲突和 Controller 入口透传。

#### LCK-03 完成记录

- 新增 `TaskExecutionGuard`，统一封装任务级 Redis 锁、任务状态 CAS 抢占和锁释放。
- `async_task_record` 增加 `claimRunning` 条件更新，仅允许未过期的 `CREATED` 任务原子切换为 `RUNNING`。
- 导入、导出执行入口统一通过任务类型和 taskId 加锁，重复投递、人工重试和恢复投递不会重复执行同一任务。
- 锁获取失败或 CAS 抢占失败时直接跳过，不修改任务失败状态，避免重复执行污染原任务。
- 增加执行锁配置：`TASK_EXECUTION_LOCK_KEY_PREFIX`、`TASK_EXECUTION_LOCK_TTL_SECONDS`。
- 新增 `TaskExecutionGuardTest`，覆盖锁被占用、成功抢占后释放和 CAS 抢占失败释放锁场景。

#### LCK-04 完成记录

- 文件直传完成、分片完成和分片取消统一使用上传任务级 Redis 分布式锁。
- `SUCCESS` 状态重复 complete 直接回查已生成的 `file_record`，不再次访问 MinIO、不重复写库。
- `ABORTED` 状态重复 abort 直接返回，不重复更新数据库、不重复删除分片对象。
- 已完成任务再次 abort 时直接跳过，避免误删正式文件。
- 上传操作锁配置为 `FILE_CENTER_UPLOAD_OPERATION_LOCK_KEY_PREFIX`、
  `FILE_CENTER_UPLOAD_OPERATION_LOCK_TTL_SECONDS`。
- 新增测试覆盖重复 complete、重复 abort 和锁被占用时跳过执行。

### 2. 数据一致性与补偿机制

目标是让 MySQL、Redis、MinIO、异步任务之间出现部分成功时有记录、可恢复、可人工处理。

| 编号 | 任务 | 主要改动 | 验收标准 |
| --- | --- | --- | --- |
| CON-01 | 补偿任务记录表 | 新增 `compensation_record`，字段包含 `compensation_id`、`biz_type`、`biz_id`、`failure_type`、`status`、`retry_count`、`next_retry_at`、`payload` | 关键链路补偿事项能入库；支持 pending/running/success/failed/ignored 状态 |
| CON-02 | 文件中心对象对账 | 定时扫描文件记录和上传任务，检查 MinIO 对象是否存在，清理过期分片和孤儿对象 | 对象缺失会标记文件异常；孤儿分片可生成删除补偿；对账结果有日志和统计 |
| CON-03 | 导入导出任务补偿 | 扫描异常 RUNNING、SUCCESS 但对象缺失、FAILED 可重试任务，生成补偿或自动重投递 | 应用重启/对象上传失败后，任务最终能恢复为明确终态或进入补偿待处理 |
| CON-04 | 管理端补偿重放接口 | 新增管理接口分页查询、重试、忽略补偿记录 | 管理员可查看补偿原因、最近错误、重试次数，并能手动触发处理 |

#### CON-01 完成记录

- 新增 `compensation_record` 补偿记录表、Flyway V8 迁移脚本和本地初始化 SQL。
- 新增 `common.compensation` 通用补偿模块，包含实体、状态枚举、失败类型枚举、Mapper 和 Service。
- 补偿记录支持 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED`、`IGNORED` 状态，以及 `retry_count`、`max_retry_count`、`next_retry_at`、`payload`、`last_error` 字段。
- 异步任务恢复投递失败时写入 `ASYNC_TASK + RECOVERY_SUBMIT_FAILED` 补偿记录。
- 文件中心下载签名生成时发现 MinIO 对象缺失，会标记文件删除并写入 `FILE + OBJECT_MISSING` 补偿记录。
- 清理过期上传任务时，如果正式对象或分片对象清理失败，会写入 `FILE_UPLOAD + CLEANUP_OBJECT_FAILED` 补偿记录。
- 补偿记录写入失败只打印日志，不阻断原业务失败处理、文件元数据标记或清理批次推进。
- 单元测试覆盖补偿记录创建、活动记录复用、状态流转、补偿写入失败不阻断，以及三个业务接入点。

#### CON-02 完成记录

- 新增 `FileObjectReconciliationService` 和 `FileObjectReconciliationServiceImpl`，按固定延迟定时执行文件对象对账。
- 对账任务通过 Redis 分布式锁保护，避免多实例同时扫描 MinIO 和文件元数据。
- `file_record` 增加按 ID 游标批量扫描能力，对正常文件记录与 MinIO 对象列表做比对。
- 发现正常文件记录对应 MinIO 对象缺失时，自动将文件标记为 `DELETED`，并写入 `FILE + OBJECT_MISSING` 补偿记录。
- `file_upload_task` 增加按 ID 游标扫描能力，收集上传任务正式对象和分片前缀，避免把合法上传残留误判为孤儿对象。
- 对 `files/general` 和 `files/multipart` 前缀下的 MinIO 对象做反向对账，发现数据库无归属对象时写入 `ORPHAN_OBJECT` 补偿记录。
- 对超过 `FILE_CENTER_RECONCILIATION_UPLOAD_STALE_HOURS` 的上传中任务，检测主对象和分片对象是否遗留，并生成 `FILE_UPLOAD + ORPHAN_OBJECT` 补偿记录。
- MinIO 对象列表失败时不会误删数据库文件，只记录 `FILE_STORAGE + CLEANUP_OBJECT_FAILED` 补偿，等待后续补偿或人工处理。
- 新增 `FileReconciliationResult` 返回扫描数量、缺失文件、超期上传、孤儿对象、清理失败和补偿记录数量。
- 单元测试覆盖文件缺失、孤儿对象、超期上传残留、对象列表失败和分布式锁跳过场景。

#### CON-03 完成记录

- 新增 `ExcelTaskCompensationCoordinator`，通过 Redis 分布式锁保护导入导出任务补偿扫描，避免多实例重复对账。
- `async_task_record` 增加按任务类型、状态和 ID 游标扫描能力，用于批量检查终态任务对象一致性。
- 导出 `SUCCESS` 任务如果结果对象在 MinIO 中明确缺失，会生成 `EXPORT + OBJECT_MISSING` 补偿记录，并将任务归档为 `EXPIRED`。
- 导入 `FAILED` 任务如果错误明细对象明确缺失，会生成 `IMPORT + OBJECT_MISSING` 补偿记录，但保留原失败状态，避免覆盖校验失败语义。
- 导入依赖源文件在失败任务中明确缺失时，会生成 `IMPORT + OBJECT_MISSING` 补偿记录，便于人工判断是否重传源文件。
- MinIO 连接失败、服务不可用等非明确缺失场景不会误记为对象丢失，等待下一轮扫描或依赖恢复。
- 导出结果上传失败、导入源文件上传失败、导入错误明细上传失败会生成 `OBJECT_UPLOAD_FAILED` 补偿记录。
- 已有 `TaskRecoveryCoordinator` 继续负责 `CREATED` 老任务和 `RUNNING` 心跳超时任务恢复投递，恢复投递失败仍写入 `ASYNC_TASK + RECOVERY_SUBMIT_FAILED`。
- 新增任务补偿配置：`EXCEL_TASK_COMPENSATION_ENABLED`、扫描延迟、批大小、锁 key 和锁 TTL。
- 单元测试覆盖导出成功对象缺失、导入错误明细缺失、MinIO 临时异常不误报、分布式锁占用跳过扫描。

#### CON-04 完成记录

- 新增 `/api/admin/compensations/page`、`/{compensationId}/retry`、`/{compensationId}/ignore` 管理接口。
- 管理接口统一使用 `PermissionService.requireAdmin()`，非管理员返回 `SECURITY_FORBIDDEN`。
- 补偿分页支持业务类型、业务 ID、失败类型、状态集合和创建时间范围过滤。
- 手动重试会将补偿记录切回 `PENDING` 并立即可调度，不再误递增失败重试次数。
- 单元测试覆盖分页过滤、手动重试、非法状态和权限异常语义。

### 3. 统一异常和响应体系

目标是让同步接口、异步任务和后台补偿使用一致的错误语义，便于接口测试和前端处理。

| 编号 | 任务 | 主要改动 | 验收标准 |
| --- | --- | --- | --- |
| ERR-01 | 错误码字典收敛 | 建立模块化错误码枚举，替换零散 `IllegalArgumentException`/`IllegalStateException` 文案 | 常见业务失败都有稳定 code；接口不会把内部异常类名或堆栈暴露给前端 |
| ERR-02 | 业务异常上下文增强 | `BusinessException` 增加 `bizId`、`retryable`、`suggestion`；异步任务失败字段复用同一语义 | 任务失败详情和接口错误响应中的失败类型、建议动作保持一致 |
| ERR-03 | 参数校验标准化 | DTO 添加 `@NotBlank`、`@NotNull`、`@Min`、`@Max`、`@Pattern`；全局异常处理字段错误 | 参数错误返回字段级错误列表；Controller 只做入参接收和调用 Service |
| ERR-04 | 错误响应文档和测试矩阵 | 文档补齐错误码、HTTP 状态码、典型错误响应；测试覆盖参数错误、状态冲突、资源不存在 | README 和测试文档能直接作为接口联调用例参考 |

#### ERR-01 完成记录

- 新增模块化错误码枚举：`ExcelErrorCode`、`FileErrorCode`、`TaskErrorCode`、`SecurityErrorCode`、`StorageErrorCode`。
- 新增 `ErrorCodeResolver`，按请求路径、HTTP 状态和存储异常关键词解析稳定错误码。
- 全局异常处理从固定 `COMMON_*` 响应改为按模块返回 `EXCEL_*`、`FILE_*`、`TASK_*`、`SECURITY_*`、`STORAGE_*`。
- 认证拦截器未登录响应从 `COMMON_UNAUTHORIZED` 收敛为 `SECURITY_UNAUTHORIZED`。
- 保留 `CommonErrorCode` 作为非模块化通用兜底，避免未知路径和内部异常没有统一 code。
- 更新 Web 异常回归测试，导入/导出接口参数错误返回 `EXCEL_PARAM_ERROR`，文件接口参数错误返回 `FILE_PARAM_ERROR`。
- 新增 `ErrorCodeResolverTest`，覆盖 Excel、文件、任务、安全和存储异常优先级。

#### ERR-02 完成记录

- `BusinessException` 增加 `bizId`、`retryable`、`suggestion` 上下文字段，保留原有构造器兼容旧调用。
- `ApiResponse` 失败响应增加 `bizId`、`retryable`、`suggestion`，并继续统一携带 `traceId`。
- `GlobalExceptionHandler` 对业务异常返回稳定错误码、业务标识、可重试标记和建议动作。
- 异步任务失败模型已有 `failureType`、`retryable`、`failureSuggestion`，接口错误响应和任务失败详情语义保持一致。

#### ERR-03 完成记录

- 引入 `spring-boot-starter-validation`，分页、查询类 DTO 增加 `@Min`、`@Max`、`@Size` 等基础校验。
- Controller 查询入参接入 `@Valid`，稳定的长度、范围和分页边界在进入 Service 前拦截。
- `ApiResponse` 增加 `fieldErrors`，全局异常处理器对 `MethodArgumentNotValidException` 和 `ConstraintViolationException` 返回字段级错误列表。
- 保留 Service 层业务规则校验，用于跨字段范围、枚举白名单和排序白名单等业务语义。
- 新增 `GlobalExceptionHandlerTest` 覆盖字段级错误响应。

#### ERR-04 完成记录

- README 补充统一错误响应结构、典型错误码、字段错误样例和联调建议。
- 测试文档补充参数校验、补偿管理、学生查询、下载审计、指标和线程池拒绝的回归覆盖说明。
- 当前本地回归基线为 `mvn test`：38 个测试类、164 个用例全部通过。

### 4. 查询分页和复杂检索

目标是让任务、文件、学生数据从“能查”升级为“好查、稳定查、可扩展查”。

| 编号 | 任务 | 主要改动 | 验收标准 |
| --- | --- | --- | --- |
| QRY-01 | 任务中心复杂查询 | 扩展任务分页 DTO 和 Mapper XML，支持多状态、多类型、时间范围、失败类型、可重试、排序白名单 | 组合条件查询结果正确；非法排序字段被拒绝或回退默认排序 |
| QRY-02 | 文件中心复杂查询 | 文件分页支持大小范围、创建时间、扩展名集合、MD5、状态、上传类型 | 用户只能查自己的文件；管理员可按 owner 过滤 |
| QRY-03 | 学生数据查询接口 | 新增学生分页接口和 DTO，支持学号、姓名、班级、年龄、生日、导入版本等条件 | 查询使用索引友好条件；分页响应统一；不暴露内部暂存表 |
| QRY-04 | 游标分页公共模型 | 抽象 `CursorPageRequest`/`CursorPageResponse`，用于大数据量列表和导出预览 | 深分页场景不依赖大 OFFSET；游标翻页顺序稳定 |

#### QRY-01 完成记录

- `AsyncTaskPageQueryRequest` 支持 `taskTypes`、`statuses`、进度范围、可重试过滤和排序参数。
- 任务分页 Mapper 支持多状态、多类型、业务标识、失败类型、关键词、时间范围、进度范围、可重试组合查询。
- `TaskCenterServiceImpl` 对任务类型、状态、失败类型和排序字段做白名单校验，避免动态排序 SQL 注入。
- 任务详情和任务分页返回真实持久化事件列表；无事件表时回退原生命周期推导。

#### QRY-02 完成记录

- `FilePageQueryRequest` 支持扩展名集合、MD5、状态、上传类型、大小范围、创建时间范围和排序参数。
- 文件分页 Mapper 支持按 owner 隔离后的组合查询，默认只查当前用户自己的文件。
- 文件记录新增 `uploadType`，普通服务端上传标记为 `SERVER`，直传/分片上传复用上传任务类型。
- 文件排序字段限制在 `id`、`created_at`、`updated_at`、`file_size`、`original_name` 白名单内。

#### QRY-03 完成记录

- 新增 `/api/students/page` 学生数据分页查询接口。
- 查询支持学号、姓名关键字、班级、性别、年龄范围、生日范围和导入版本过滤。
- Service 只返回 `StudentResponse`，不暴露暂存表或数据库内部实体。
- MyBatis XML 增加组合查询 SQL，并保持默认按 `id DESC` 稳定排序。
- 单元测试覆盖分页边界归一化、结果转换和年龄范围校验。

#### QRY-04 完成记录

- 新增公共 `CursorPageRequest` 和 `CursorPageResponse<T>`。
- 新增 `/api/students/cursor-page`，使用 `id > cursor` 的稳定游标翻页，避免深分页 OFFSET。
- 游标分页每次多查一条判断 `hasMore`，返回 `nextCursor` 给前端继续翻页。
- 单元测试覆盖多查一条、空页游标和分页大小边界。

### 5. 限流、熔断和降级

目标是保护应用在高并发、依赖抖动和资源不足时可控失败，而不是把整机拖垮。

| 编号 | 任务 | 主要改动 | 验收标准 |
| --- | --- | --- | --- |
| RES-01 | 接口级限流 | 基于 Redis 或本地令牌桶实现用户级、接口级、全局级限流 | 超限返回统一错误码；限流指标可观测；不创建无效任务 |
| RES-02 | 外部依赖超时与重试策略 | MinIO、Redis、MySQL 关键调用配置超时、短重试和失败分类 | 依赖短暂抖动不会立刻失败；长时间不可用时快速失败并给出建议动作 |
| RES-03 | 降级策略 | Redis 不可用时查 MySQL；MinIO 不可用时任务可重试失败；监控不可用不影响主链路 | 单个依赖异常不会导致所有接口 500；降级行为有日志和指标 |
| RES-04 | 线程池拒绝策略可观测 | 导入/导出/补偿线程池拒绝时记录拒绝事件、指标和统一响应 | 队列满时接口明确返回系统繁忙；不会留下半创建任务 |

#### RES-01 完成记录

- 新增 `RateLimitProperties`、`RateLimitService`、`RateLimitInterceptor` 和 Web 配置。
- 对导入提交、导出提交、普通上传、直传初始化、分片初始化、文件下载签名、导出下载签名、导入错误文件下载签名增加限流。
- 限流同时检查用户维度和全局维度，超限返回 HTTP 429，并写入 `Retry-After` 响应头。
- 限流参数支持配置：`RATE_LIMIT_ENABLED`、窗口秒数、用户阈值、全局阈值和清理阈值。
- 新增 `RateLimitServiceTest`，覆盖用户维度超限和关闭限流两类场景。

#### RES-02 完成记录

- MinIO 客户端增加连接、读、写超时配置，并启用 OkHttp 连接失败重试。
- MinIO 关键操作补充短重试：路径文件上传、对象读取、对象 stat、签名 URL、分片合并等。
- Redis 已配置连接超时；任务中心 Redis 缓存读写失败继续降级到 MySQL 或只打印告警，不阻断主状态流转。
- MySQL 导入写库沿用批次事务超时、死锁/瞬断重试和回退等待校验；任务失败会按依赖异常或资源限制分类。
- 新增配置：`MINIO_CONNECT_TIMEOUT_MILLIS`、`MINIO_WRITE_TIMEOUT_MILLIS`、`MINIO_READ_TIMEOUT_MILLIS`、`MINIO_MAX_RETRY_TIMES`、`MINIO_RETRY_BACKOFF_MILLIS`。

#### RES-03 完成记录

- 任务中心 Redis 读写失败继续保留 MySQL 作为权威状态，不因缓存异常中断任务创建、查询或状态流转。
- MinIO 上传和对象缺失已按依赖异常分类为可重试失败或补偿记录，避免 HTTP 线程长时间阻塞。
- 监控指标写入使用 Micrometer 本地注册，不参与业务事务，不影响主链路。
- 依赖异常仍通过任务失败分类和补偿记录暴露，便于后续重试或人工处理。

#### RES-04 完成记录

- 导出任务、导入任务和导入 worker 线程池统一使用可观测拒绝策略。
- 线程池拒绝时记录结构化 WARN 日志，包含线程池名称、活跃线程数、队列长度和剩余容量。
- 线程池拒绝会写入 `demo.thread.pool.rejected.total{pool=...}` 指标。
- 业务提交入口已捕获拒绝异常并把任务置为可重试失败，不留下无终态任务。
- 单元测试覆盖拒绝策略指标记录。

### 6. 监控和可观测性

目标是让问题能被发现、定位和复盘，覆盖日志、指标、事件和审计。

| 编号 | 任务 | 主要改动 | 验收标准 |
| --- | --- | --- | --- |
| OBS-01 | 任务事件日志持久化 | 新增 `async_task_event_log`，任务中心每次关键状态变化写事件 | 任务详情能返回真实事件列表，不再只依赖时间字段合成 |
| OBS-02 | TraceId 贯穿异步链路 | HTTP traceId 写入任务记录/事件日志，后台日志 MDC 增加 taskId、traceId、workerId | 从一次接口提交可以串起后台执行、MinIO 上传、任务终态日志 |
| OBS-03 | 下载审计查询接口 | 下载审计增加分页 Mapper、Service、Controller，支持 owner/admin 查询 | 可按资源类型、资源 ID、时间范围查询下载记录 |
| OBS-04 | 业务指标补齐 | Micrometer 增加导入/导出速率、文件上传耗时、补偿积压、失败分类计数 | Prometheus 可抓取核心业务指标；文档说明指标含义和告警建议 |

#### OBS-01 完成记录

- 新增 `async_task_event_log` 实体、Mapper、XML 和 Flyway V9 迁移脚本。
- 任务创建、运行、进度更新、重试、取消、失败、成功、过期都会写入任务事件日志。
- 任务事件记录进度、完成数、总数、失败类型、traceId、workerId 和发生时间。
- 任务详情接口返回持久化事件列表；分页查询也会附带每个任务的最近事件视图。

#### OBS-02 完成记录

- `async_task_record` 增加 `trace_id`，任务创建时从 HTTP `X-Trace-Id` 或系统生成 TraceId 自动写入。
- `AsyncTaskResponse` 和 `AsyncTaskEventResponse` 返回 traceId，便于前端或测试报告串联异步链路。
- 导入/导出后台线程执行时写入 MDC：`taskId`、`traceId`、`workerId`。
- Flyway V9 同步补齐 `trace_id`、`upload_type` 和 `async_task_event_log`，避免已有库结构缺字段。

#### OBS-03 完成记录

- 新增 `/api/download-audits/page` 下载审计分页接口。
- 普通用户只能查询自己的审计记录；管理员可按 ownerId 过滤。
- 支持资源类型、资源 ID 和创建时间范围过滤。
- 单元测试覆盖 owner 隔离、管理员 owner 过滤和时间范围校验。

#### OBS-04 完成记录

- `TaskMetricsService` 补齐业务指标：导入/导出处理行数、处理耗时、行速率、MinIO 上传耗时、错误文件生成数、补偿积压采样和线程池拒绝数。
- 导出完成后记录导出行速率，导出结果上传记录存储耗时和成功/失败标签。
- 异步导入记录源文件上传、错误明细上传、导入行速率和错误文件生成结果。
- 新增 `TaskMetricsServiceTest` 覆盖核心指标注册和计数。

## 已完成历史任务

| 状态 | 优先级 | 任务 | 关键内容 |
| --- | --- | --- | --- |
| DONE | 历史 | 项目初始化 | 基于 Spring Boot、EasyExcel、MyBatis、MySQL 搭建学生导入导出演示项目 |
| DONE | 历史 | 配置安全改造 | 使用 `application.yml`，数据库密码等敏感信息改为环境变量 |
| DONE | 历史 | 数据库建表脚本 | 提供 `create_database.sql`、`create_tables.sql`、`schema.sql` |
| DONE | 历史 | MySQL + MyBatis 写库 | 使用 MyBatis XML 批量 `INSERT ... ON DUPLICATE KEY UPDATE` |
| DONE | 历史 | 导入模板接口 | 提供学生 Excel 导入模板下载 |
| DONE | 历史 | 百万级导出 | 游标分页、快照边界、异步任务、本地临时文件、MinIO 上传和签名下载 |
| DONE | 历史 | Redis 任务状态 | 导出任务状态缓存 Redis，并持久化任务记录到 MySQL |
| DONE | 历史 | 文件上传中心 | 普通上传、客户端直传、秒传、分片上传、分页查询和静态测试页 |
| DONE | 历史 | 统一异步任务中心 | 抽象任务创建、运行中、成功、失败、取消、过期、重试和分页查询 |
| DONE | P1 | 任务中心重试与失败分级 | 引入失败类型、可重试标记和建议动作，避免坏任务反复压垮系统 |
| DONE | 历史 | 真正异步导入 | 导入接口立即返回任务 ID，后台线程解析 Excel 并写库 |
| DONE | 历史 | 全量原子导入 | 使用 `student_import_stage` 暂存表，校验通过后合并正式表 |
| DONE | P0 | 导入错误明细文件 | 导入校验失败时生成错误 Excel，上传 MinIO，并提供签名下载入口 |
| DONE | P0 | 导入文件持久化到 MinIO | 提交导入任务时保存源 Excel 到 MinIO，后台执行和重试从对象存储读取 |
| DONE | P1 | 报表运行控制中心 | 保存学生报表查询条件，基于运行控制创建导出任务并查看历史运行 |
| DONE | P1 | 通用报表导出引擎 | 抽象 Sheet 配置、快照计数、游标分页、Excel 写入、进度更新和取消检查 |
| DONE | P1 | 任务监控和指标 | 接入 Actuator/Micrometer，记录任务计数、耗时和线程池快照 |
| DONE | P1 | 任务恢复与补偿调度 | 增加 worker 心跳、悬挂任务扫描、CAS 抢占和导入/导出恢复投递 |
| DONE | P1 | 统一响应和异常处理 | 增加 `ApiResponse`、错误码、业务异常、全局异常处理和 JSON 响应包装 |
| DONE | P1 | API 权限和用户体系 | 增加当前用户上下文、demo/auth 模式、Bearer token 和 owner 数据隔离 |
| DONE | P1 | 业务异常上下文增强 | 业务异常与统一响应支持 bizId、retryable、suggestion，并和任务失败详情保持语义一致 |
| DONE | P1 | 任务中心复杂查询 | 任务分页支持多状态、多类型、时间范围、进度范围、可重试和白名单排序 |
| DONE | P1 | 文件中心复杂查询 | 文件分页支持大小范围、创建时间、上传类型、状态、MD5、扩展名集合和稳定排序 |
| DONE | P1 | 接口级限流 | 导入导出、上传初始化和下载签名接口增加用户维度、全局维度限流 |
| DONE | P1 | 外部依赖超时与重试策略 | MinIO 增加超时和短重试配置，Redis/MySQL 失败分类和降级路径完成收敛 |
| DONE | P1 | 任务事件日志持久化 | 持久化任务关键生命周期事件，并在任务详情中返回真实事件列表 |
| DONE | P1 | TraceId 贯穿异步链路 | HTTP traceId 写入任务记录和事件日志，后台执行日志带 taskId、traceId、workerId |
| DONE | P1 | 数据库迁移版本管理 | 增加 Flyway 依赖、版本化迁移脚本、baseline 配置和生产初始化说明 |
| DONE | P1 | 框架层参数异常映射 | 修复 R7 中 3 个 500，统一映射为 400/415，并补本地回归测试 |
| DONE | P1 | 接口扁平化脚本加固 | 导出下载和任务重试按任务实际终态动态断言，避免空库取消竞态和超单 Sheet 边界误判 |
| DONE | P2 | 数据归档和清理策略 | 增加可配置定时清理，按批清理终态任务、上传任务、逻辑删除文件和暂存数据 |
| DONE | P1 | 批次和分页参数配置化 | `IMPORT_BATCH_SIZE`、`EXPORT_PAGE_SIZE` 支持环境变量配置，并对导入批次和导出分页做范围保护 |
| DONE | P1 | 百万级导入稳定性护栏 | 增加导入行数/文件大小保护、恢复默认失败、连接池容量校验和启动资源摘要日志 |
| DONE | P1 | 导入分块合并 | 先完整校验暂存表，再按 `IMPORT_MERGE_CHUNK_SIZE` 分块 upsert 正式表，降低长事务风险 |
| DONE | P2 | Excel 导入文件头校验 | 修复 F-12，提交阶段校验 `.xlsx` 后缀、zip 文件头和 xlsx 必要结构，非法文件直接 400 |
| DONE | P2 | 性能压测和调优报告 | 固化 R7/R10 标准环境导入导出结论、风险边界和复现脚本 |
| DONE | P2 | 文档脱敏和测试产物治理 | 删除原始联调 JSON，真实地址、签名 URL 和测试 Token 改为占位符或环境变量 |
| DONE | P1 | CI/CD 自动回归门禁 | 增加 GitHub Actions 单测门禁、手动/定时集成测试和失败产物上传 |
| DONE | P1 | 文件上传断点续传完整化 | 增加分片恢复接口、本地草稿持久化和恢复后签名刷新 |
| DONE | P1 | 异步任务查询增强 | 任务列表支持业务标识、失败类型、关键字和创建时间范围筛选，详情补充耗时、剩余重试次数、worker、心跳和生命周期摘要 |
| DONE | P1 | 下载链路统一签名化 | 导出文件、导入错误文件和通用文件下载统一返回 MinIO 短期签名 URL，并记录下载审计 |
| DONE | P1 | 导入预检模式 | 新增 `/api/excel/import/precheck`，提交前快速返回结构、容量和前 N 行字段问题预览 |
| DONE | P2 | 文件配额与生命周期 | 增加单文件、总存储、活跃上传任务和每日上传次数配额，清理过期未完成上传任务和临时对象 |
| DONE | P1 | 全量原子导入版本切换 | 引入 `import_version` 和版本控制表，导入构建新版本后一次性发布可见版本 |
| DONE | P2 | 导入历史版本清理 | 保留当前版本和最近 1 个历史版本，定时清理更旧的 `import_version` 数据 |
| DONE | P2 | 导入/导出限流策略 | 增加全局和用户维度并发保护，避免高峰任务挤爆线程池和数据库 |
| DONE | P1 | 多实例部署一致性治理 | 任务恢复 CAS 抢占、清理分布式锁、对象缺失元数据标记和恢复协调器测试 |
| DONE | P1 | 统一分布式锁组件 | 新增 Redis 分布式锁服务，清理任务和恢复调度复用同一锁组件 |
| DONE | P1 | 接口幂等键机制 | 新增幂等记录表和服务，导入/导出/文件上传初始化支持 `Idempotency-Key` |
| DONE | P1 | 补偿任务记录表 | 新增通用补偿记录模型，任务恢复投递失败、文件对象缺失和上传清理失败可入库追踪 |
| DONE | P1 | 文件中心对象对账 | 定时对账文件记录、上传任务和 MinIO 对象，缺失对象标记删除，孤儿对象生成补偿记录 |
| DONE | P1 | 导入导出任务补偿 | 定时扫描导出/导入终态任务与 MinIO 对象一致性，上传失败和对象缺失进入补偿记录 |
| DONE | P1 | 错误码字典收敛 | 按 common/task/excel/file/security/storage 分类整理错误码，全局异常响应按模块返回稳定 code |
| DONE | P2 | 轻量管理页面 | 新增 `/ops-dashboard.html`，支持运维概览、任务查询/取消/重试、补偿查询/重试/忽略和下载审计查看 |
| DONE | P1 | 导入模式策略 | 导入接口支持 `OVERWRITE`、`APPEND`、`VALIDATE_ONLY`，任务 payload 和结果记录模式，追加/覆盖/仅校验冲突规则已文档化 |
| DONE | P1 | Grafana Dashboard 和告警规则 | 新增 Prometheus 告警规则 YAML、Grafana Dashboard JSON，并补齐 MinIO 慢上传和补偿积压排障步骤 |
| DONE | P2 | 导出 CSV / 多文件兜底 | 导出接口支持 XLSX、CSV 和 ZIP 分片 CSV，超大数据可绕开单 Sheet 上限 |
| DONE | P2 | 监控面板和告警规则 | 增加 Prometheus endpoint、指标说明、Grafana 面板建议、告警规则和排障手册 |
| DONE | P2 | 回归数据集治理 | 新增统一 fixture 生成脚本和数据集说明，样本默认输出到 target/test-fixtures |
| DONE | P2 | 错误行在线预览 | 导入失败任务 resultPayload 保存错误摘要和前 100 行预览，并提供预览接口 |
| DONE | P2 | 文件上传测试页增强 | 测试页支持 baseURL、分片状态、断点继续、失败重试和日志可视化 |

---

## 1. 导入错误明细文件

状态：DONE

### 目标

导入失败时，不只返回粗粒度错误信息，还要生成一份错误明细 Excel，用户可以下载后看到每一行失败原因，修正后重新上传。

### 背景

当前导入已经能在写正式表前完整校验暂存数据，但失败信息还比较粗，例如：

- 必填字段为空
- 文件内存在重复 `student_no`
- 数据长度超过数据库字段限制
- 邮箱格式不合法
- 年龄不是合理范围

这些错误如果只返回总数，用户很难定位具体数据。

### 需求范围

1. 新增错误明细模型
   - `rowNo`：Excel 数据行号
   - `studentNo`：学号
   - `name`：姓名
   - `errorMessage`：错误原因，多个错误用分号拼接
   - 原始字段：年龄、性别、班级、邮箱、生日

2. 校验规则
   - `studentNo` 必填，长度不超过 32
   - `name` 必填，长度不超过 64
   - `gender` 长度不超过 16
   - `className` 长度不超过 64
   - `email` 长度不超过 128，可选做邮箱格式校验
   - `birthday` 长度不超过 32，可选做日期格式校验
   - 同一文件内 `studentNo` 不允许重复

3. 错误文件生成
   - 使用 EasyExcel 生成 `student-import-error-{taskId}.xlsx`
   - 上传到 MinIO，例如 `excel/student/import-error/`
   - 任务失败时在 `resultPayload` 中保存错误文件 `objectKey`

4. 下载接口
   - `GET /api/excel/import/{taskId}` 返回错误文件信息
   - `GET /api/excel/import/{taskId}/error-file` 下载错误明细
   - 仅任务归属用户可下载
   - 返回 302 MinIO 签名地址

### 数据库建议

当前实现选择不新增错误表，直接扫描 `student_import_stage` 暂存表生成错误文件。后续如果需要长期保留错误明细，可新增：

```sql
CREATE TABLE student_import_error (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    import_task_id VARCHAR(64) NOT NULL,
    row_no INT NOT NULL,
    student_no VARCHAR(32),
    error_message VARCHAR(1024) NOT NULL,
    created_at DATETIME NOT NULL,
    KEY idx_student_import_error_task_row (import_task_id, row_no)
);
```

### 验收标准

- 上传包含空 `student_no` 的 Excel，任务失败，正式表不变。
- 任务详情能看到错误文件信息。
- 下载错误文件后，能看到具体错误行和错误原因。
- 上传包含重复 `student_no` 的 Excel，所有重复行都能在错误文件中体现。
- 错误文件不长期公开访问，下载必须走签名 URL。

### 完成记录

- 新增 `StudentImportErrorRow` 错误明细 Excel 行模型。
- 新增 `StudentImportValidationException` 携带错误行。
- 暂存表字段扩容，避免超长数据在暂存阶段提前失败。
- Mapper 支持查询必填、长度、年龄、邮箱格式和重复学号错误行。
- 导入失败时生成错误 Excel，并上传到 MinIO。
- 导入任务结果保存 `errorCount`、`errorFileName`、`errorObjectKey`。
- 新增导入状态查询和错误文件下载接口。

---

## 2. 导入文件持久化到 MinIO

状态：DONE

### 目标

导入任务提交后，将原始 Excel 文件保存到 MinIO，任务重试时从 MinIO 读取文件，避免应用重启、本地临时目录清理或多实例部署导致任务不可重试。

### 背景

当前异步导入会先把上传文件保存到本地临时目录，再由后台线程读取。单机开发没问题，但生产环境存在几个风险：

- 应用重启后临时文件可能丢失
- 多实例部署时任务在 A 节点提交，重试落到 B 节点找不到文件
- 本地磁盘空间不可控
- 任务重试强依赖本地文件仍然存在

### 需求范围

1. 文件保存
   - 导入接口收到文件后上传到 MinIO
   - 对象路径建议：`excel/student/import-source/{yyyyMMdd}/{importId}.xlsx`
   - 任务 payload 保存 `sourceObjectKey`、`originalName`、`fileSize`

2. 后台读取
   - `StudentImportTaskServiceImpl` 后台执行时从 MinIO 获取输入流
   - 本地临时文件只作为可选缓存，不作为任务恢复的唯一来源

3. 重试行为
   - 失败、取消、过期任务允许重试时，从 MinIO 原文件重新解析
   - 如果源文件对象不存在，任务失败并提示“导入源文件不存在或已过期”

4. 生命周期
   - MinIO 为 `excel/student/import-source/` 配置生命周期
   - 默认保留 1 到 7 天，可通过配置控制

### 配置建议

```yaml
app:
  minio:
    import-source-object-prefix: ${MINIO_IMPORT_SOURCE_OBJECT_PREFIX:excel/student/import-source}
    import-source-retention-days: ${MINIO_IMPORT_SOURCE_RETENTION_DAYS:1}
```

### 验收标准

- 提交导入任务后，MinIO 能看到原始导入文件。
- 删除本地临时目录后，重试任务仍能执行。
- 应用重启后，失败任务仍能重试。
- 源文件过期被删除后，重试任务明确失败并记录原因。

### 完成记录

- 新增导入源文件 `sourceObjectKey` 和 `fileSize` 任务 payload 字段，保留 `temporaryFilePath` 兼容历史任务。
- 提交导入任务时将源 Excel 上传到 MinIO `excel/student/import-source/{yyyyMMdd}/student-import-{businessKey}.xlsx`。
- 后台导入和任务重试统一从 MinIO 打开源文件输入流。
- 如果任务创建失败，会清理已经上传的源对象。
- 如果源对象不存在或生命周期过期，任务失败并提示“导入源文件不存在或已过期”。
- 为导入源文件前缀配置 MinIO 生命周期，默认 1 天后清理。

---

## 3. 报表运行控制中心

状态：DONE

### 目标

参考调度类异步导出报表规范，增加“运行控制”能力：用户可以保存报表查询条件，点击运行后生成导出任务，并查看历史运行记录。

### 背景

当前学生导出是直接导出全量学生表，没有复杂条件，也没有运行参数保存。企业级报表通常需要：

- 保存用户自己的查询条件
- 同一个用户维护多个运行控制
- 点击运行后异步生成文件
- 查看本人历史运行结果

### 接口设计

基础路径建议：`/api/report/student-runs`

| 接口名称 | 请求方式 | 路径 | 说明 |
| --- | --- | --- | --- |
| 分页查询运行控制 | POST | `/page` | 查询当前用户创建的运行控制 |
| 创建运行控制 | POST | `/create` | 保存一组查询条件 |
| 查询运行控制详情 | GET | `/{runId}` | 查看运行条件 |
| 修改运行控制 | POST | `/{runId}/update` | 修改查询条件 |
| 删除运行控制 | POST | `/{runId}/delete` | 逻辑删除运行控制 |
| 运行报表 | POST | `/{runId}/run` | 基于运行控制创建导出任务 |
| 查询运行历史 | POST | `/{runId}/tasks` | 查询该运行控制下的历史导出任务 |

### 表设计建议

```sql
CREATE TABLE student_report_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    run_control_code VARCHAR(64) NOT NULL,
    run_name VARCHAR(128) NOT NULL,
    student_no VARCHAR(32),
    name_keyword VARCHAR(64),
    class_name VARCHAR(64),
    gender VARCHAR(16),
    min_age INT,
    max_age INT,
    status VARCHAR(32) NOT NULL,
    deleted BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_student_report_run_run_id (run_id),
    UNIQUE KEY uk_student_report_run_owner_code (owner_id, run_control_code, deleted),
    KEY idx_student_report_run_owner_created_at (owner_id, created_at),
    KEY idx_student_report_run_owner_status_created_at (owner_id, status, created_at)
);
```

### 业务规则

- `owner_id + run_control_code` 唯一。
- 用户只能查询、修改、运行自己的运行控制。
- 删除采用逻辑删除。
- 运行控制只保存查询条件，不直接保存导出文件。
- 每次点击运行都创建新的 `EXPORT` 任务。

### 验收标准

- 用户 A 看不到用户 B 的运行控制。
- 同一用户重复创建相同 `runControlCode` 会失败。
- 修改运行控制后，再运行报表使用最新条件。
- 运行报表后，可以在任务中心查询对应任务。

### 完成记录

- 新增 `student_report_run` 表和 MyBatis Mapper，支持运行控制创建、修改、逻辑删除、分页和详情。
- 新增 `/api/report/student-runs` 接口组，覆盖创建、分页、详情、修改、删除、运行和历史任务查询。
- 运行控制支持 `studentNo`、`nameKeyword`、`className`、`gender`、`minAge`、`maxAge` 查询条件。
- 学生导出任务 payload 支持携带查询条件，导出仍使用 `id > lastId AND id <= snapshotMaxId` 游标分页。
- 基于运行控制创建导出任务时，使用 `runId` 作为任务 `businessKey`，可按运行控制查询历史导出任务。
- 删除后的运行控制 `deleted` 写入本行 id，允许同一用户重新创建相同 `runControlCode`。

---

## 4. 通用报表导出引擎

状态：DONE

### 目标

把当前学生导出中的任务提交、分页查询、Excel 写入、MinIO 上传、任务状态更新抽象成通用报表引擎。后续新增一个报表时，只需要实现查询参数、Sheet 配置和分页查询逻辑。

### 背景

当前 `ExportTaskServiceImpl` 和学生业务强绑定：

- 文件名写死
- Sheet 写死
- Excel VO 写死
- 查询逻辑写死
- 只支持单 Sheet

如果后续继续加报表，会复制很多代码。

### 实现设计

1. 通用 Job 接口

```java
public interface ReportExportJob<P> {

    String buildFileName(String businessKey, P params);

    Long resolveSnapshotMaxId(P params);

    List<ReportSheetConfig> getSheetConfigs(P params);

    long count(P params, ReportSheetConfig sheetConfig, Long snapshotMaxId);

    ReportPage queryPage(P params, ReportSheetConfig sheetConfig, ReportPageCursor cursor);
}
```

2. Sheet 配置

```java
public class ReportSheetConfig {
    private int sheetIndex;
    private String sheetName;
    private Class<?> headClass;
}
```

3. 支持能力
   - 单 Sheet
   - 多 Sheet 配置入口
   - 空数据导出表头
   - 导出取消检查
   - 导出进度更新
   - 单 Sheet 行数上限校验
   - 游标必须推进的保护校验

4. 后续增强
   - 条件行
   - 自定义列宽
   - 多级表头
   - CSV 报表输出

### 验收标准

- 学生导出迁移为 `StudentReportExportJob`。
- 新增一个简单报表时，不需要复制快照计数、游标分页、Excel 写入、进度更新和取消检查逻辑。
- 多 Sheet 报表可以通过多个 `ReportSheetConfig` 复用同一写入引擎。
- 空数据报表仍能生成表头。
- 任务取消后不继续上传最终文件。

### 完成记录

- 新增 `ReportExportEngine`，统一处理 Sheet 遍历、行数上限、游标分页、Excel 写入、进度更新和取消检查。
- 新增 `ReportExportJob`、`ReportSheetConfig`、`ReportPageCursor`、`ReportPage`、`ReportExportCommand`、`ReportExportResult` 等报表模型。
- 新增 `StudentReportExportJob`，将学生报表的文件名、Sheet 配置、快照边界、分页查询和 Excel 行转换从 `ExportTaskServiceImpl` 中拆出。
- `ExportTaskServiceImpl` 保留任务创建、任务状态转换、临时文件、MinIO 上传和下载 URL 逻辑。
- 新增 `ReportExportEngineTest`，覆盖分页写入、空数据表头和单 Sheet 行数超限失败。

---

## 5. 任务监控和指标

状态：DONE

### 目标

为导入、导出、文件上传和任务中心增加基础可观测性，方便定位慢任务、失败任务、队列堆积和外部依赖异常。

### 需求范围

1. 线程池指标
   - 导入任务线程池 active count
   - 导入 worker 线程池 active count
   - 导出线程池 active count
   - queue size
   - completed task count
   - rejected task count

2. 任务指标
   - 导入任务成功数、失败数、取消数
   - 导出任务成功数、失败数、取消数
   - 平均耗时、最大耗时
   - 每分钟任务提交量

3. 数据库批次指标
   - 暂存批次耗时
   - 最终合并耗时
   - 导出分页查询耗时
   - 单页数据量

4. MinIO 指标
   - 上传耗时
   - 下载签名生成失败次数
   - 对象删除失败次数

### 技术建议

- 引入 Spring Boot Actuator。
- 如需更完整，可接 Micrometer + Prometheus。
- 先用日志埋点也可以，但字段要稳定，便于后续采集。

### 验收标准

- 可以看到当前导入/导出线程池活跃线程和队列长度。
- 任务失败时能通过 taskId 串起日志。
- 100 万导入能输出解析、暂存、合并三个阶段耗时。
- MinIO 上传失败和数据库写入失败能区分。

### 完成记录

- 新增 Spring Boot Actuator 和 Micrometer。
- 新增 `TaskMetricsService`，记录异步任务提交、运行、成功、失败、取消、过期等计数和任务耗时。
- 新增 `ThreadPoolMetricsConfig`，注册导入任务、导入 worker 和导出线程池指标。
- 新增 `/api/tasks/metrics/thread-pools`，返回线程池核心线程数、活跃线程数、队列长度和完成任务数。
- 保留 taskId 贯穿任务日志，导入、导出和 MinIO 异常仍按业务阶段记录。

---

## 6. 性能压测和调优报告

状态：DONE

### 完成记录

- 新增 `docs/performance-report.md`，含测试机器/部署、JVM、数据量、参数矩阵（可调项 vs 代码常量）、结果表格、推荐配置、暂存校验导入成本、风险与边界、复现步骤。
- 新增可复现脚本：`scripts/gen_perf_import_file.py`（流式生成百万行导入文件）、`scripts/perf_bench.py`（按任务 `startedAt/finishedAt` 计算纯异步处理吞吐）；沿用 `scripts/import_load_test.py` 并发矩阵。
- R7 标准环境实测：1M 导出 3/3 成功，平均约 23,317 行/s；100k 导入 3/3 成功，平均约 3,908 行/s。
- R10 补充验证：放开护栏后 1M 导入 SUCCESS，平均约 4,521 行/s，分块合并消除了单长事务风险。
- R7 明确小规格单机 Docker 环境默认不适合直接跑百万级导入：已复现 OOM 和长事务风险，swap 只能缓解 100k 级别稳定性。
- 历史本地 DB 基线仅作为高配参考：4/6/8/16 worker 约 23.4k/30.3k/29.2k/63.8k 行/s，导出约 15.7k 行/s。
- 完成 README、测试说明、压测说明和复盘文档的脱敏，原始联调 JSON 不再入库。

### 目标

形成一份可复现的导入导出压测报告，说明不同数据量、部署拓扑和关键参数对吞吐量与稳定性的影响。

### 背景

项目已经经历过多轮导入导出性能测试，但结果分散在日志和 README 中。后续需要把压测方式、环境、参数和结论固化，方便复盘和面试讲解。

### 压测维度

1. 导入 worker 数
   - 1、2、4、6、8、16
   - 观察吞吐、CPU、数据库连接占用、锁等待

2. 导入批次大小
   - 500、1000、2000、5000、10000
   - 观察内存、SQL 长度、单批耗时

3. Hikari 连接池
   - 5、10、20、30
   - 观察 worker 数与连接池大小的关系

4. 暂存校验导入前后对比
   - 旧方案：批次直接 upsert 正式表
   - 新方案：暂存表 + 校验通过后合并正式表
   - 对比总耗时、失败恢复能力、正式表一致性

5. 导出分页大小
   - 1000、3000、5000、10000
   - 观察查询耗时、Excel 写入耗时、内存占用

### 输出文档建议

新增文档：`docs/performance-report.md`

内容包括：

- 测试机器配置
- MySQL/Redis/MinIO 部署方式
- JVM 参数
- 数据量
- 参数矩阵
- 结果表格
- 曲线图或简化统计
- 最终推荐配置
- 风险和边界

### 验收标准

- 至少覆盖 10 万、100 万两个数据量；当 100 万在当前硬件不可行时，要明确给出不可行原因。
- 关键场景至少重复 3 次，避免单次波动误判。
- 给出推荐配置和不建议执行的边界，例如当前标准环境单次导入优先控制在 10 万行级别。
- 明确说明暂存校验导入带来的额外成本。

---

## 7. 任务恢复与补偿调度

状态：DONE

### 目标

应用重启、线程池拒绝、进程异常退出后，系统能够识别并处理悬挂任务，避免 `CREATED` 或 `RUNNING` 状态长期停留。

### 背景

当前任务中心已经有 MySQL 持久化和 Redis 缓存，但真正的执行线程仍在应用进程内。应用突然退出时，数据库中可能保留：

- 已创建但还没被线程池执行的 `CREATED` 任务
- 已经开始但进程中断的 `RUNNING` 任务
- Redis 状态过期但 MySQL 仍未终态的任务

这些任务需要通过补偿机制统一处理。

### 需求范围

1. 任务执行心跳
   - 任务开始后定期更新 `last_heartbeat_at`
   - 记录执行节点标识，例如 `worker_id` 或 `instance_id`
   - 导入、导出任务都接入心跳

2. 启动恢复
   - 应用启动时扫描本实例或无实例归属的可恢复任务
   - `CREATED` 任务可重新投递执行
   - 超过心跳超时时间的 `RUNNING` 任务标记为失败或重新投递

3. 并发控制
   - 多实例下通过 Redis 分布式锁或数据库状态 CAS 抢占任务
   - 保证同一任务不会被两个节点同时执行

4. 状态处理
   - 不可恢复任务标记为 `FAILED`，失败原因写明“任务执行节点异常退出”
   - 可恢复任务重试次数受 `maxRetryCount` 限制

### 验收标准

- 人为杀掉应用后，重启能处理遗留 `CREATED` 任务。
- 人为杀掉导入/导出中的应用后，超时任务不会长期停留在 `RUNNING`。
- 多实例同时启动时，同一个任务只会被一个实例恢复执行。
- 恢复行为有清晰日志，可以通过 `taskId` 追踪。

### 完成记录

- `async_task_record` 新增 `worker_id` 和 `last_heartbeat_at`。
- 任务进入 RUNNING、更新进度、成功、失败和取消时会同步心跳信息。
- 新增 `TaskRecoveryCoordinator`，定时扫描超时 CREATED/RUNNING 任务。
- 新增 `TaskRecoveryHandler`，导入和导出服务均支持恢复投递。
- 恢复前通过数据库条件更新抢占任务，避免多实例重复执行同一任务。

---

## 8. 统一响应和异常处理

状态：DONE

### 目标

统一所有 Controller 的成功响应、错误响应、错误码和异常日志格式，避免不同接口返回结构不一致。

### 背景

当前接口返回形式混合存在：

- DTO 对象
- `Map<String, Object>`
- `ResponseEntity<Void>`
- `ResponseStatusException`
- `IllegalArgumentException`

Demo 阶段可以接受，但企业项目需要稳定的响应契约，便于前端统一处理。

### 需求范围

1. 统一响应对象
   - 新增 `ApiResponse<T>`
   - 字段建议：`success`、`code`、`message`、`data`、`traceId`、`timestamp`
   - 文件下载和 302 跳转接口可保留 `ResponseEntity`

2. 统一异常体系
   - 新增 `BusinessException`
   - 新增错误码枚举，例如 `CommonErrorCode`
   - 参数错误、资源不存在、状态冲突、外部依赖失败分开定义

3. 全局异常处理
   - 新增 `GlobalExceptionHandler`
   - 不向前端返回堆栈、SQL、MinIO 详细异常
   - 日志中保留 `taskId`、`fileId`、`runId` 等关键标识

4. 接口迁移
   - Excel、任务中心、文件中心、报表运行控制逐步迁移
   - README 同步响应结构示例

### 验收标准

- 常规 JSON 接口都返回统一结构。
- 参数错误返回稳定错误码和 HTTP 400。
- 资源不存在返回稳定错误码和 HTTP 404。
- 任务状态冲突返回稳定错误码和 HTTP 409。
- 服务端日志包含异常堆栈，但响应体不暴露内部细节。

### 完成记录

- 新增 `ApiResponse<T>`，统一 `success`、`code`、`message`、`data`、`traceId` 和 `timestamp`。
- 新增 `CommonErrorCode`、`ErrorCode` 和 `BusinessException`。
- 新增 `GlobalExceptionHandler`，统一处理业务异常、参数异常、状态冲突和未捕获异常。
- 新增 `ApiResponseBodyAdvice`，对常规 JSON Controller 响应做统一包装，下载和 302 接口保持原行为。
- 新增 `RequestTraceFilter`，为请求和响应添加 `X-Trace-Id`。

---

## 9. API 权限和用户体系

状态：DONE

### 目标

把当前基于 `X-User-Id` 的模拟归属改造成更真实的登录用户上下文，避免用户伪造请求头访问别人的任务、文件或报表运行控制。

### 背景

当前项目没有登录系统，任务归属通过请求头区分。这个方式适合本地演示，但生产环境存在明显风险：

- 用户可以伪造 `X-User-Id`
- 缺少登录态校验
- 缺少接口权限标识
- 文件、任务、运行控制只做弱隔离

### 需求范围

1. 用户上下文
   - 新增统一 `CurrentUser`
   - `TaskOwnerResolver` 改为从认证上下文读取用户 ID
   - 保留本地 demo 模式，方便无登录测试

2. 认证机制
   - 可选 Spring Security + JWT
   - 本地提供简单登录接口或固定测试用户
   - 敏感接口必须认证后访问

3. 权限控制
   - 给导入、导出、文件中心、报表运行控制增加权限点
   - 管理员可查看全量任务，普通用户只能查看自己的任务

4. 数据隔离
   - 所有基于 `ownerId` 的查询统一从认证上下文获得
   - 下载签名 URL 前必须校验资源归属

### 验收标准

- 未登录访问受保护接口返回 401。
- 普通用户无法通过伪造请求头访问其他用户任务。
- 管理员接口和普通用户接口边界清晰。
- 本地 demo 模式仍可一键启动测试。

### 完成记录

- 新增 `CurrentUser`、`UserContextHolder` 和 `UserContextInterceptor`。
- 默认 demo 模式兼容 `X-User-Id`；关闭 demo 模式后必须使用 Bearer token。
- `TaskOwnerResolver` 改为优先读取当前认证用户。
- 任务中心、报表运行控制继续按 ownerId 隔离。
- 文件中心新增 `owner_id`，正式文件、直传任务、分片任务、秒传、详情、下载、删除和分页均按当前用户隔离。

---

## 10. 数据库迁移版本管理

状态：DONE

### 目标

引入 Flyway 或 Liquibase 管理表结构变更，替换启动时自动建表和多份 SQL 手工同步，降低生产环境 DDL 风险。

### 背景

当前项目同时存在：

- Mapper XML 中的 `CREATE TABLE IF NOT EXISTS`
- `create_tables.sql`
- `schema.sql`
- 应用启动时自动初始化表

这种方式在 Demo 中很方便，但后续表结构变更容易出现脚本不一致、线上 DDL 不可控、索引重复创建等问题。

### 需求范围

1. 引入迁移工具
   - 优先选择 Flyway
   - 脚本目录建议：`src/main/resources/db/migration`
   - 版本命名：`V1__init_schema.sql`、`V2__add_report_run.sql`

2. 拆分初始化逻辑
   - 生产模式关闭 Mapper 自动建表
   - 本地 demo 可保留一键初始化开关
   - README 说明两种模式差异

3. 迁移脚本管理
   - 所有新增表、字段、索引走版本脚本
   - 历史脚本不可修改，只能新增版本
   - 补充回滚或降级说明

4. 兼容已有环境
   - 对已存在的表支持 baseline
   - 避免重复创建唯一索引导致启动失败

### 验收标准

- 空库启动时能通过 Flyway 初始化所有表。
- 已有库接入时可 baseline，不破坏已有数据。
- 删除 Mapper XML 中生产不需要的 DDL 初始化职责。
- README 清楚说明本地和生产的数据库初始化方式。

### 完成记录

- 新增 Flyway 依赖和 `spring.flyway` 配置，默认关闭，生产可通过环境变量开启。
- 新增 `src/main/resources/db/migration/V1__init_schema.sql` 初始化完整表结构。
- 新增 `V2__add_async_task_heartbeat.sql`，幂等补齐任务心跳字段。
- 新增 `V3__add_file_owner.sql`，幂等补齐文件 owner 隔离字段。
- README 增加 Flyway、本地自动建表和生产初始化建议。

---

## 11. 框架层参数异常映射

状态：DONE

### 目标

修复 R7 接口扁平化测试中稳定复现的 F-02：部分 Spring 框架层异常被统一兜底成 500，应该返回 400 或 415。

### 背景

当前业务异常和部分参数异常已经有统一响应，但以下场景仍暴露为 `COMMON_INTERNAL_ERROR`：

- 路径变量类型错误，例如 `/api/excel/seed/abc`
- multipart 接口未携带 `file` part
- 不支持的请求媒体类型

这类问题属于客户端请求错误，不应该表现为服务端内部错误。

### 需求范围

1. 全局异常处理
   - 增加 `MethodArgumentTypeMismatchException` 映射为 HTTP 400。
   - 增加 `MissingServletRequestPartException` 映射为 HTTP 400。
   - 增加 `HttpMediaTypeNotSupportedException` 映射为 HTTP 415。
   - 响应体继续使用 `ApiResponse`，保留 `traceId`。

2. 错误信息
   - 对前端返回简短、稳定、可读的错误原因。
   - 日志保留异常类型、请求路径和 traceId。
   - 不向响应体暴露堆栈、SQL、对象存储内部异常。

3. 测试
   - 覆盖 `seed/abc`。
   - 覆盖 Excel 导入未携带 file。
   - 覆盖文件中心上传未携带 file。
   - 覆盖不支持的 Content-Type。

### 验收标准

- R7 中 F-02 三个失败用例全部变为通过。
- `mvn test` 通过。
- 错误响应结构仍为 `{success, code, message, data, traceId, timestamp}`。

### 完成记录

- `GlobalExceptionHandler` 补充 `MethodArgumentTypeMismatchException`，路径变量类型错误返回 HTTP 400。
- `GlobalExceptionHandler` 补充 `MissingServletRequestPartException`，缺少 multipart `file` 参数返回 HTTP 400。
- `GlobalExceptionHandler` 补充 `HttpMediaTypeNotSupportedException`，不支持的 Content-Type 返回 HTTP 415。
- 新增本地 MockMvc 回归用例，覆盖 `seed/abc`、Excel 导入缺 file、文件中心上传缺 file。
- 本地 `mvn test` 通过：12 个测试类 / 54 用例全部通过。

---

## 12. 百万级导入稳定性护栏

状态：DONE

### 目标

防止用户在小规格环境直接提交超出容量边界的导入任务，避免再次出现 OOM、数据库不可用、SSH 无响应等整机级故障。

### 背景

R7 标准环境已验证，R10 已补充百万级成功验证：

- 100k 导入 3/3 成功，平均约 25.6s。
- 1M 导入默认受容量护栏限制；放开后在标准环境 SUCCESS，平均约 4,521 行/s。
- swap 可以缓解 100k 稳定性，百万级导入还需要分块合并和容量护栏共同兜底。

### 需求范围

1. 导入行数/文件大小保护
   - 增加 `IMPORT_MAX_ROWS_PER_TASK` 或等价配置。
   - 增加 `IMPORT_MAX_FILE_SIZE_FOR_ASYNC` 或复用 multipart 限制。
   - 超过限制时任务拒绝，并返回明确错误信息。

2. 资源保护
   - 标准环境默认 worker 降为更保守值，例如 4 或 6。
   - 启动时校验 `worker * 并发任务 <= Hikari - 预留连接数`。
   - 增加最小可用内存或 swap 检查日志，至少启动时给出风险提示。

3. 恢复保护
   - 恢复协调器对同一失败任务设置重派次数上限。
   - 对 OOM/进程异常后恢复的任务，默认先标记失败，避免自动重跑大文件。
   - 心跳超时阈值调短并环境化。

### 验收标准

- 超过配置行数或文件大小的导入会被明确拒绝。
- 拒绝不会创建后台 worker，不会写入暂存表。
- 小文件和 100k 文件导入不受影响。
- 崩溃恢复不会反复重派同一高风险导入任务。

### 完成记录

- 新增 `IMPORT_MAX_ROWS_PER_TASK`，提交阶段扫描 xlsx 工作表行数，超过限制直接 400，不创建异步任务。
- 新增 `IMPORT_MAX_FILE_SIZE_FOR_ASYNC`，导入文件超过配置字节数时直接拒绝。
- 提交阶段已校验 `.xlsx` 后缀、zip 文件头和 xlsx 必要结构，避免非 Excel 文件进入后台任务。
- `IMPORT_AUTO_RECOVERY_ENABLED` 默认 `false`，异常退出后的导入任务会标记失败并要求用户重新提交，避免大文件自动反复重跑。
- 导入 worker 总容量继续受 `HIKARI_MAXIMUM_POOL_SIZE` 约束，防止写库线程数超过数据库连接池。
- `StudentServiceImpl` 启动时输出导入资源摘要，包括 JVM 内存、物理内存、swap、worker 配置、行数上限和文件大小上限，方便标准环境判断容量边界。
- 新增单元测试覆盖非 Excel 拒绝、行数超限、文件大小超限、恢复默认失败和批次限幅。

---

## 13. 导入分块合并

状态：DONE

### 目标

替代历史百万级导入的单事务合并方式，降低长事务、事务超时、undo/redo 压力和锁持有时间，同时尽量保持正式表一致性。

### 背景

历史实现的最终阶段是单事务：

```text
student_import_stage -> student_record
```

100k 级别已稳定，但按 R7 吞吐外推，1M 暂存和合并很可能超过 `IMPORT_TRANSACTION_TIMEOUT_SECONDS=60`。如果单纯把事务超时调大，会延长锁持有和失败恢复时间。

### 方案取舍

本次选择“暂存表完整校验 + 分块事务 upsert”：

- 解析、暂存、必填字段、长度、格式和文件内重复 `student_no` 全部校验通过后，才允许进入正式表合并。
- 合并阶段按 `IMPORT_MERGE_CHUNK_SIZE` 切块，每块一个短事务，默认 `5000` 行。
- 该方案能显著降低单个长事务的锁持有、undo/redo 和超时风险。
- 该阶段曾经不是严格全量原子；后续第 18 项已经补齐 `import_version` 可见版本切换，分块写入未发布版本，发布失败不会影响当前可见版本。

### 需求范围

- 增加 `IMPORT_MERGE_CHUNK_SIZE` 配置。
- Mapper 支持按 `row_no` 范围从 `student_import_stage` upsert 到 `student_record`。
- 合并前统一校验暂存行数、必填字段、长度、格式和文件内重复学号。
- 记录每个合并块耗时、影响行数和行号范围。
- 文档明确说明分块合并与后续版本切换的一致性语义。

### 验收标准

- 100k 导入耗时不明显退化。
- 1M 导入不再依赖单个超长事务。
- 合并中途失败时，任务会失败并清理暂存表；第 18 项完成后，未发布版本也会按 `import_task_id` 清理。
- 文档明确说明新的“一致性语义”。

### 完成记录

- `StudentMapper` 新增 `mergeImportStageRangeToStudent(importTaskId, startRowNo, endRowNo, importVersion)`。
- `StudentServiceImpl` 的最终合并改为先校验暂存表，再按 `IMPORT_MERGE_CHUNK_SIZE` 分块执行短事务。
- 分块合并日志包含 `importTaskId`、`startRowNo`、`endRowNo`、`affectedRows` 和耗时。
- 成功、失败、取消统一在外层清理本次 `student_import_stage` 数据，成功路径不再重复删除暂存表。
- 新增单元测试验证 5 行数据在 chunkSize=2 时按 `1-2`、`3-4`、`5-5` 三块合并，且校验失败不会进入合并。

---

## 14. 批次和分页参数配置化

状态：DONE

### 目标

将导入批次大小和导出分页大小从代码常量改为配置项，支持不同环境做参数矩阵压测。

### 背景

当前压测报告中仍标记两个限制：

- 导入批次大小固定 2000。
- 导出分页大小固定 5000。

这会限制后续调优，例如小规格云盘可能需要更小批次降低事务峰值，高配本地环境可能可以尝试更大批次减少 round trip。

### 需求范围

1. 配置项
   - `IMPORT_BATCH_SIZE`
   - `EXPORT_PAGE_SIZE`
   - 增加最小值、最大值和默认值。

2. 启动校验
   - 导入批次建议范围：500 到 5000。
   - 导出分页建议范围：1000 到 10000。
   - 配置超限时启动失败或回退并打印告警。

3. 文档和测试
   - README 增加配置说明。
   - 压测脚本记录本次配置。
   - 单元测试覆盖默认值、过小、过大。

### 验收标准

- 不改代码即可调整导入批次和导出分页。
- 压测报告能记录配置值。
- 错误配置不会导致单页内存过高或 SQL 过大。

### 完成记录

- `application.yml` 已支持 `IMPORT_BATCH_SIZE` 和 `EXPORT_PAGE_SIZE` 环境变量。
- 导入批次在提交任务时归一化到 `500` 到 `5000`。
- 导出分页已有 `1000` 到 `10000` 的范围保护。
- 新增单元测试覆盖导入批次过大时被限制到 `5000`。

---

## 15. 文件安全治理

状态：DONE

### 目标

为导入 Excel、导出文件、通用文件中心增加安全校验和治理能力，避免任意文件上传、伪造类型、恶意内容和敏感文件长期保留。

### 背景

当前文件中心重点实现了上传能力，包括普通上传、直传、秒传和分片上传。但生产环境还需要关注安全：

- 文件扩展名和真实内容可能不一致
- 客户端传入的 `contentType` 不可信
- Excel 文件可能包含异常压缩包结构
- 文件下载 URL 需要严格归属校验和有效期控制
- 恶意文件可能长期停留在对象存储

### 需求范围

1. 文件类型校验
   - Excel 导入只允许 `.xlsx`
   - 通用文件中心配置允许的扩展名和 MIME 类型
   - 使用文件头或解析器做内容嗅探，不只信任后缀

2. 文件安全扫描
   - 抽象 `FileSecurityScanner`
   - Demo 先实现 no-op 或规则扫描
   - 后续可接 ClamAV、对象存储事件扫描或第三方服务

3. 下载安全
   - 所有下载 URL 生成前校验文件归属、状态、过期时间
   - 签名 URL 有效期可按文件类型配置

4. 审计记录
   - 记录上传人、下载人、IP、文件大小、文件 hash
   - 可查询异常上传记录

### 验收标准

- 上传 `.exe` 改名 `.xlsx` 不能通过 Excel 导入。
- 文件中心可配置允许上传类型。
- 下载别人的文件返回 404 或 403。
- 安全扫描失败时文件不会变成 `NORMAL` 状态。

### 完成记录

- 新增 `FileSecurityScanner` 接口和规则型实现 `RuleBasedFileSecurityScanner`。
- 文件中心普通上传、直传完成、分片完成都接入了内容扫描。
- 新增文件后缀白名单、MIME 白名单和内容嗅探配置，默认开启安全扫描。
- 补充文件中心单测，覆盖合法文本文件、合法 ZIP 型 Office 文件和伪装可执行文件的拦截。
- 本地验证通过：
  - `JAVA_HOME=<JDK8_HOME> <MAVEN_HOME>/bin/mvn -q -Dtest=FileCenterServiceImplTest,RuleBasedFileSecurityScannerTest test`
  - `JAVA_HOME=<JDK8_HOME> <MAVEN_HOME>/bin/mvn -q test`

---

## 16. 集成测试和回归数据集

状态：DONE

### 完成记录

- 提供 `docker-compose-test.yml`（隔离 MySQL 8.0.32 / Redis 6.2 / MinIO，独立网络与数据卷，端口 23306/26379/29000 可覆盖）与 `scripts/run_integration_tests.sh`（一键：起依赖 → 打包 → 起应用 → 跑扁平化套件 → 默认清理，`KEEP_INTEGRATION_DEPS=1` 可保留排查）。
- **真实依赖联调闭环已验证**：以标准测试环境（真实 MySQL/Redis/MinIO + 部署版应用）执行全量接口扁平化测试，R7–R11 累计 5 轮全绿（最终 77/77，含导入/导出/分块合并/护栏/安全扫描全链路），等效覆盖本任务的全部用例目标（导入成功、校验失败、导出签名下载、报表运行控制、文件中心直传/分片/秒传）。
- 回归数据集：`run_flat_tests.py` 自动生成夹具（合法/非法/伪装文件、直传分片二进制），`gen_perf_import_file.py` 流式生成 10 万~百万行数据集。
- 决策记录（2026-08-18）：按项目负责人要求，不再另起隔离测试环境执行闭环，直接以标准环境真实依赖联调作为集成验证结论；本地脚本保留给有 Docker 的开发机使用。

### 目标

使用真实 MySQL、Redis、MinIO 环境验证核心链路，降低 H2、Mock 和真实依赖之间的行为差异。

### 背景

当前单元测试已经覆盖了不少核心分支，但仍有一些真实环境差异无法靠 Mock 或 H2 完整覆盖：

- MySQL `INSERT ... ON DUPLICATE KEY UPDATE`
- MySQL 事务、锁等待、唯一索引冲突
- MinIO 生命周期、签名 URL、对象不存在
- Redis 缓存过期和短暂不可用
- 大文件流式解析和上传

### 需求范围

1. 测试环境
   - 提供 `docker-compose-test.yml`
   - 启动 MySQL、Redis、MinIO
   - 支持本地一键执行集成测试

2. 测试用例
   - 导入成功
   - 导入校验失败并生成错误文件
   - 导入源文件过期后重试失败
   - 导出成功并生成签名下载 URL
   - 报表运行控制创建、运行、查询历史
   - 文件中心直传、分片上传、秒传

3. 回归数据集
   - 准备小数据 Excel
   - 准备错误数据 Excel
   - 准备重复学号 Excel
   - 准备较大数据量生成脚本

### 验收标准

- 一条命令可以启动依赖并跑完集成测试。
- 集成测试不依赖个人本机路径和真实服务器地址。
- 测试结束后能清理容器和临时对象。
- README 有清晰的集成测试执行说明。

### 当前进展

- 新增 `docker-compose-test.yml`，使用本地隔离端口启动 MySQL、Redis、MinIO。
- 新增 `scripts/run_integration_tests.sh`，支持一键启动测试依赖、打包应用、启动应用、执行 `scripts/run_flat_tests.py`，完成后默认清理容器和数据卷。
- `scripts/run_flat_tests.py` 已补充文件安全回归用例，并修正直传测试文件类型，避免把文本内容伪装为 ZIP。
- `scripts/gen_api_test_cases.py` 和 `docs/test/接口扁平化测试用例.xlsx` 已同步到 135 条用例。
- 本机已验证脚本语法、Python 编译和相关单测；当前环境没有 `docker` 命令，真实容器联调待 Docker 环境执行。

---

## 17. 数据归档和清理策略

状态：DONE

### 目标

为任务记录、文件记录、上传任务、导入暂存数据和对象存储元数据设计统一保留策略，防止表和对象无限增长。

### 背景

当前 MinIO 已经配置部分生命周期规则，但 MySQL 中的任务记录、文件记录、上传任务和导入暂存数据仍需要治理。长期运行后可能出现：

- `async_task_record` 表越来越大
- `file_upload_task` 中失败或中断任务长期保留
- `file_record` 逻辑删除后没有归档
- MinIO 对象被生命周期删除，但 MySQL 元数据仍显示可下载
- 导入暂存表异常情况下存在残留数据

### 需求范围

1. 保留策略配置
   - 任务记录保留天数
   - 上传任务保留天数
   - 逻辑删除文件保留天数
   - 导入暂存数据保留天数

2. 定时清理
   - 清理过期 `async_task_record`
   - 清理过期 `file_upload_task`
   - 清理逻辑删除文件及对应 MinIO 对象
   - 清理异常残留 `student_import_stage`

3. 元数据修复
   - MinIO 对象不存在时，文件记录标记为不可下载或异常
   - 支持手动触发一致性检查

4. 归档方案
   - 重要任务可归档到历史表
   - 普通 Demo 数据可直接物理删除

### 验收标准

- 定时任务可以按配置清理过期记录。
- 清理行为不会删除仍在运行中的任务或正在上传的分片。
- 对象已过期时，下载接口返回明确错误。
- 清理日志包含数量和耗时，便于审计。

### 完成记录

- 新增 `CleanupProperties`，支持 `DATA_CLEANUP_*` 环境变量配置启停、调度间隔、批大小和各类数据保留时间。
- 新增 `RetentionCleanupService`，定时按批清理：
  - `async_task_record` 中 `SUCCESS`、`FAILED`、`CANCELED`、`EXPIRED` 终态任务
  - `file_upload_task` 中 `SUCCESS`、`ABORTED` 上传任务
  - `file_record` 中 `DELETED` 文件记录，并同步删除 MinIO 对象
  - `student_import_stage` 中超过保留期的暂存数据
- 清理逻辑不会删除运行中的异步任务，也不会删除 `UPLOADING` 状态的上传任务。
- 下载前会先校验对象是否仍存在；若对象已被生命周期清理或手工删除，下载接口返回空地址并由 Controller 按 404 处理。
- README 已补充数据清理环境变量说明。
- 本地验证通过：
  - `JAVA_HOME=<JDK8_HOME> <MAVEN_HOME>/bin/mvn -q -Dtest=RetentionCleanupServiceTest,FileCenterServiceImplTest,RuleBasedFileSecurityScannerTest test`

---

## 18. 全量原子导入版本切换

状态：DONE

### 目标

在现有“暂存表 + 全量校验 + 分块合并”的基础上，实现可见版本原子切换语义：导入新版本构建完成前，旧版本继续对外可见；新版本只有最后发布成功后才对查询和导出生效。

### 背景

分块合并能显著降低长事务、锁持有和 OOM 风险，但历史实现中分块一旦写入正式表就会被当前查询读到。为兼顾百万级短事务和“失败不污染当前可见数据”，本次引入 `import_version` 和版本控制表。

### 需求范围

1. 版本模型设计
   - `student_record` 新增 `import_version` 和 `import_task_id`。
   - 新增 `student_import_version_control` 保存当前可见版本。
   - 导入成功后通过一次 CAS 更新切换当前版本。

2. 数据写入路径
   - Excel 仍先写入 `student_import_stage`。
   - 校验通过后写入新 `import_version` 数据。
   - 切换生效前，线上查询仍读取旧版本。

3. 失败处理
   - 解析、暂存、校验、构建新版本任一阶段失败时，旧版本继续对外服务。
   - 切换前失败可清理新版本数据。
   - 切换后新版本已对外确认，不再回滚。

4. 数据清理
   - 当前已清理发布失败的未发布版本数据。
   - 后续已经补齐版本历史清理，避免历史版本无限增长。

### 验收标准

- 构造 10 万行导入，切换前查询仍返回旧数据。
- 构造最终校验失败文件，正式可见版本不变化。
- 构造构建新版本过程中失败，正式可见版本不变化，临时版本可清理。
- 成功导入后只通过一次版本切换生效。
- README 和测试文档明确说明“分块合并”和“版本切换”两种模式差异。

### 完成记录

- `student_record` 增加 `import_version`、`import_task_id`、`uk_student_record_version_student_no`、`idx_student_record_version_id` 和 `idx_student_record_import_task_id`。
- 新增 `student_import_version_control`，启动初始化和 Flyway 迁移都会创建并初始化当前版本。
- 导入合并阶段先读取当前版本，生成独立新版本号，分块写入未发布版本。
- 发布阶段通过 `promoteStudentVersion(expectedVersion, newVersion)` CAS 更新当前可见版本；更新失败说明已有其他导入发布，当前任务失败。
- 构建新版本或发布失败时，按 `import_task_id` 清理未发布数据，旧版本继续对外服务。
- 普通查询、导出计数和导出游标分页都按当前/快照版本过滤。
- 导出任务 payload 新增 `snapshotVersion`，一次导出固定读取同一个版本和最大 id 边界。
- 新增 `StudentReportExportJobTest` 覆盖导出按快照版本查询，新增导入发布失败清理测试。
- 本地验证通过：`JAVA_HOME=<JDK8_HOME> <MAVEN_HOME>/bin/mvn test`，15 类 / 77 用例全部通过。

---

## 19. CI/CD 自动回归门禁

状态：DONE

### 目标

将当前本地验证流程自动化，确保每次 push 或 PR 都能自动执行编译、单元测试、文档检查和可选集成测试。

### 背景

当前项目已经有 `mvn test`、接口扁平化脚本、集成测试脚本和测试用例矩阵，但主要依赖人工执行。随着功能越来越多，手工回归容易漏跑，尤其是文件上传、导入导出和任务中心之间存在跨模块依赖。

### 需求范围

1. GitHub Actions 工作流
   - JDK 8 环境。
   - Maven 缓存。
   - 执行 `mvn test`。
   - 执行 `git diff --check` 或等价空白检查。

2. 集成测试工作流
   - 使用 `docker-compose-test.yml` 启动 MySQL、Redis、MinIO。
   - 执行 `scripts/run_integration_tests.sh`。
   - 支持手动触发和定时触发。

3. 产物和报告
   - 上传 surefire 报告。
   - 上传接口扁平化测试结果。
   - 失败时保留应用日志和容器日志。

4. 安全策略
   - 不在 workflow 中写真实服务器地址和密钥。
   - 测试 Token 使用 GitHub Secrets 或本地测试默认值。

### 验收标准

- push 到分支后自动执行单元测试。
- 手动触发集成测试可以启动容器并跑完接口扁平化脚本。
- 失败时能下载测试报告和日志。
- README 增加 CI/CD 执行说明和失败排查入口。

### 完成记录

- 新增 `.github/workflows/ci.yml`：
  - push / pull request 到 `main` 自动触发。
  - 使用 JDK 8 和 Maven 缓存。
  - 执行 `git diff --check` 与 `mvn -B test`。
  - 无论成功失败都上传 `target/surefire-reports/`。
- 新增 `.github/workflows/integration.yml`：
  - 支持 `workflow_dispatch` 手动触发。
  - 每周定时执行一次。
  - 使用 `docker-compose-test.yml` 启动 MySQL、Redis、MinIO。
  - 执行 `scripts/run_integration_tests.sh` 跑真实依赖接口闭环。
  - 失败时上传 `target/integration-test/`、`target/surefire-reports/` 和 `docs/test/live-test-results.json`。
- 加固 `scripts/run_integration_tests.sh`：
  - 启动应用前显式等待 MySQL、Redis、MinIO 就绪。
  - 中间件未就绪时输出对应容器尾部日志。
- README 增加 CI/CD 触发方式、产物位置和敏感信息约束说明。

---

## 20. 多实例部署一致性治理

状态：DONE

### 目标

补齐多实例部署场景下的任务调度、文件下载、对象清理和元数据一致性能力，避免单机 Demo 假设在多节点环境失效。

### 背景

当前导入源文件、导出文件和上传文件都已经进入 MinIO，天然比本地临时目录更适合多实例。但任务执行、恢复、清理和下载仍需要明确多节点协作规则，例如 A 节点创建任务、B 节点恢复任务、C 节点处理下载请求时，必须得到一致结果。

### 需求范围

1. 任务抢占和心跳
   - 每个任务记录 `workerId`、心跳时间和执行节点。
   - 恢复任务使用 CAS 抢占，避免多个节点重复执行。
   - 节点下线后由其他节点接管超时任务。

2. 文件元数据一致性
   - 下载前校验对象是否存在。
   - 对象不存在时更新文件或任务结果状态。
   - 任务结果中的 objectKey 与 file_record 能关联追踪。

3. 清理协同
   - 多节点清理任务使用分布式锁或数据库抢占。
   - 避免多个节点重复删除同一个对象。
   - 清理失败可重试并记录原因。

4. 部署说明
   - README 增加单机、多实例、对象存储和反向代理拓扑说明。

### 验收标准

- 两个应用实例同时启动时，同一任务只会被一个节点执行。
- 手工停止执行节点后，超时任务能被另一个节点识别并补偿。
- MinIO 对象被手动删除后，下载接口返回 404，数据库元数据可标记异常。
- 清理任务在多实例下不会出现重复删除导致的异常噪音。

### 完成记录

- 任务恢复已有 worker 心跳和数据库 CAS 抢占。
- 定时保留清理已增加 Redis 分布式锁，多个应用实例同时调度时只有一个实例执行清理。
- 文件对象缺失时已将 `file_record` 标记为 `DELETED`，避免假正常数据继续参与查询和秒传。
- 导出文件对象缺失时会将对应导出任务标记为 `EXPIRED`，避免成功态任务长期保留失效下载地址。
- 导入错误文件对象缺失时也会将对应导入任务标记为 `EXPIRED`，避免失败态任务长期保留失效下载地址。
- 新增 `TaskRecoveryCoordinatorTest` 覆盖恢复开关、无 handler 跳过、CAS 抢占失败跳过、抢占成功投递和恢复投递异常落失败。
- 本地单元测试已覆盖多实例恢复的核心互斥语义；标准环境双节点部署后仍建议按同一验收标准做人工演练复核。

---

## 21. 任务中心重试与失败分级

状态：DONE

### 目标

把任务失败从单一失败文本升级为结构化失败分类，并基于分类决定是否允许重试、如何退避、是否需要告警。

### 背景

当前任务中心已经支持失败、取消和重试，但不同失败类型的处理策略应该不同。例如参数错误和文件格式错误不应该自动重试；MinIO 短暂不可用、数据库死锁、网络超时可以有限重试；容量护栏失败应提示用户拆分数据。

### 需求范围

1. 失败分类
   - `VALIDATION_ERROR`：参数、文件格式、业务校验失败。
   - `DEPENDENCY_ERROR`：MySQL、Redis、MinIO 等外部依赖异常。
   - `RESOURCE_LIMIT`：文件过大、行数过多、线程池队列满。
   - `SYSTEM_ERROR`：未分类系统异常。
   - `CANCELED`：用户取消或系统取消。

2. 重试策略
   - 可重试失败才允许重试。
   - 支持指数退避或固定退避。
   - 记录重试来源、次数、上次失败原因。

3. API 返回
   - 任务详情返回失败类型、是否可重试、建议动作。
   - 重试不可用时返回明确 409 和业务原因。

4. 告警预留
   - 同一失败类型连续出现超过阈值时记录告警日志或指标。

### 验收标准

- 非 Excel 文件提交阶段直接拒绝，不产生可重试任务。
- MinIO 短暂不可用失败可重试，重试次数受上限控制。
- 容量护栏失败返回拆分建议。
- 任务详情能展示结构化失败类型和可重试状态。

### 完成记录

- `async_task_record` 增加 `failure_type`、`retryable` 和 `failure_suggestion` 字段，并补齐 Flyway 增量迁移与初始化脚本。
- 任务中心写入失败时按分类保存结构化失败信息，任务详情返回失败类型、是否可重试和建议动作。
- 导入校验失败标记为 `VALIDATION_ERROR` 且不可重试；普通系统/依赖失败保留重试入口。
- 重试入口会拒绝不可重试失败，避免坏任务反复压垮系统。
- 相关单元测试已补齐并通过 `mvn test` 验证。

---

## 22. 导出 CSV / 多文件兜底

状态：DONE

### 目标

为超过 Excel 单 Sheet 上限或不需要回导语义的超大数据量导出提供 CSV 或多文件兜底方案。

### 背景

当前导出坚持单 Sheet，是为了保证导出的 `.xlsx` 可以直接回导。但 Excel 单 Sheet 最大数据行数约 104 万，超过后只能失败。对于报表归档、离线分析、审计导出等场景，CSV 或多文件压缩包更适合大数据量。

### 需求范围

1. 导出模式
   - `XLSX_SINGLE_SHEET`：当前模式，保证可回导。
   - `CSV`：单文件 CSV，适合大数据量。
   - `ZIP_CSV_PARTS`：按固定行数拆 CSV 后打包。

2. 接口参数
   - 导出接口支持指定 `format`。
   - 运行控制支持保存默认导出格式。
   - 超过单 Sheet 时可提示选择 CSV。

3. 文件生成
   - CSV 使用流式写入，避免大内存。
   - 多文件导出按行数或文件大小切分。
   - MinIO 对象类型和文件名后缀正确。

4. 回导约束
   - README 明确只有单 Sheet XLSX 保证可直接回导。
   - CSV 回导如需支持，另立任务。

### 验收标准

- 超过单 Sheet 上限时，XLSX 模式失败并提示 CSV 选项。
- CSV 模式能导出百万级以上数据。
- 多文件模式生成 zip，包内文件命名清晰。
- 下载接口能正确返回签名 URL 和文件名。

### 完成记录

- 新增 `StudentExportFormat`，支持 `XLSX_SINGLE_SHEET`、`CSV`、`ZIP_CSV_PARTS` 三种导出格式。
- `POST /api/excel/export?format=CSV` 可提交 CSV 导出；不传 `format` 时保持原有单 Sheet XLSX。
- `ReportExportEngine` 新增流式 CSV 写入和 ZIP 分片 CSV 写入，继续复用快照边界、游标分页、取消检查和进度更新。
- CSV 表头复用导出行模型上的 `@ExcelProperty`，避免 Excel 和 CSV 列名不一致。
- MinIO 上传支持按文件格式写入 content-type，下载仍返回签名 URL。
- 单元测试覆盖 CSV 写入、CSV 逗号转义、ZIP part 切分以及 Controller 透传格式参数。

---

## 23. 导入/导出限流策略

状态：DONE

### 目标

为导入、导出、报表运行和文件上传增加统一的并发保护，避免高峰请求压垮应用线程池、数据库连接池和对象存储。

### 背景

当前导入任务已经有并发数和 worker 限制，导出也有线程池队列，但这些限制分散在不同配置里，缺少按用户、按任务类型和全局维度的统一策略。多用户同时提交大任务时，仍可能出现队列堆积、连接池耗尽或响应不明确。

### 需求范围

1. 限流维度
   - 全局导入任务并发数。
   - 全局导出任务并发数。
   - 单用户运行中任务数。
   - 单用户每日任务提交数或文件总量。

2. 队列策略
   - 队列满时返回 429 或业务 409。
   - 返回预计等待、当前队列长度或建议稍后重试。
   - 支持管理员查看队列状态。

3. 配置化
   - 限流阈值通过环境变量配置。
   - 默认值适配小规格单机。

4. 测试
   - 单用户并发提交超过阈值被拒绝。
   - 多用户并发不互相越权。
   - 队列满时错误响应统一。

### 验收标准

- 超过单用户并发限制时返回明确错误。
- 超过全局队列容量时不会创建任务。
- 指标能展示当前运行中和排队任务数量。
- README 增加限流配置说明。

### 完成记录

- `TaskCenterProperties` 新增 `maxActiveTasksPerOwner` 和 `maxActiveTasksTotal`，支持通过环境变量配置。
- `TaskCenterServiceImpl` 在创建任务前统一校验用户活跃任务数和系统活跃任务总数。
- `AsyncTaskRecordMapper` 和 XML 新增活跃任务统计查询。
- 导入、导出和其他异步任务共享同一套任务提交节流规则。
- 单元测试覆盖用户活跃任务超限和系统活跃任务超限场景。

---

## 24. 监控面板和告警规则

状态：DONE

### 目标

基于已有 Actuator/Micrometer 指标，补充可落地的监控面板、告警规则和排障手册。

### 背景

现在系统已经暴露任务、线程池等指标，但指标本身不等于可观测。真实部署时需要知道哪些指标重要、阈值如何设置、告警后如何定位问题。

### 需求范围

1. 指标整理
   - 任务提交数、成功数、失败数、取消数。
   - 导入/导出耗时分布。
   - 线程池活跃数、队列长度、拒绝数。
   - Hikari 连接池活跃连接和等待连接。
   - MinIO 上传/下载失败数。

2. 面板设计
   - 任务中心总览。
   - 导入导出吞吐趋势。
   - 文件中心上传和分片趋势。
   - JVM、连接池和线程池资源面板。

3. 告警规则
   - 任务失败率超过阈值。
   - 队列长度持续过高。
   - 数据库连接池等待持续过高。
   - MinIO 连续失败。

4. 文档
   - 增加 Prometheus/Grafana 示例配置。
   - 增加常见告警排查路径。

### 完成记录

- 增加 `micrometer-registry-prometheus`，应用暴露 `/actuator/prometheus` 供 Prometheus 抓取。
- 新增 [monitoring-alerting.md](monitoring-alerting.md)，整理任务、线程池、Hikari、JVM 和 CPU 指标。
- 文档提供 Prometheus scrape 配置、Grafana 面板 PromQL、告警规则 YAML 和任务失败/队列堆积/连接等待/JVM 内存高的排障路径。
- README 增加监控手册入口。

### 验收标准

- README 或 docs 中有指标清单和告警阈值建议。
- 能通过 Prometheus 抓取应用指标。
- Grafana 面板能展示任务和资源核心指标。
- 人工制造 MinIO 不可用时能看到失败指标增长。

---

## 25. 回归数据集治理

状态：DONE

### 目标

把测试用到的 Excel、二进制文件和错误样本统一生成、统一命名、统一清理，提升回归测试可重复性。

### 背景

当前脚本已经能动态生成部分测试夹具，但随着导入校验、文件安全扫描、分片上传和性能测试增多，样本会越来越分散。需要一套可重复生成的数据集，而不是依赖个人临时文件。

### 需求范围

1. 数据集类型
   - 合法小 Excel。
   - 空模板。
   - 重复学号 Excel。
   - 必填缺失 Excel。
   - 超长字段 Excel。
   - 非 Excel 伪装文件。
   - 分片上传二进制样本。
   - 性能测试大文件生成脚本。

2. 生成脚本
   - 提供统一入口，例如 `scripts/gen_regression_fixtures.py`。
   - 输出到 `.tmp` 或 `target/test-fixtures`。
   - 默认不提交生成产物。

### 完成记录

- 新增 `scripts/gen_regression_fixtures.py`，统一生成合法小 Excel、空模板、重复学号、必填缺失、超长字段、伪装 Excel 和分片二进制样本。
- 新增 [regression-datasets.md](regression-datasets.md)，沉淀样本清单、命名规则、清理规则和性能样本生成方式。
- 脚本默认输出到 `target/test-fixtures/regression`，生成产物不提交。
- 已本地执行脚本验证，所有样本均能生成。

3. 用例引用
   - 接口扁平化脚本从固定目录读取或生成样本。
   - 性能脚本支持复用同一数据集。

4. 清理策略
   - 测试结束删除临时样本。
   - 大文件不入 Git。

### 验收标准

- 一条命令可以生成全量回归样本。
- 扁平化测试不依赖个人本机路径。
- 样本命名能看出用途和数据量。
- README 说明如何生成和清理测试数据集。

---

## 26. 错误行在线预览

状态：DONE

### 目标

在导入任务详情中展示错误摘要和前 N 行错误明细，让用户不下载错误 Excel 也能快速判断问题类型。

### 背景

当前导入校验失败后会生成错误文件，用户可以下载后查看。但实际排错时，很多问题只需要看到前几行错误和错误类型统计。在线预览能减少下载成本，也更接近业务系统体验。

### 需求范围

1. 错误摘要
   - 错误总数。
   - 按错误类型统计数量。
   - 是否生成错误文件。

2. 错误明细预览
   - 返回前 N 行错误。
   - 支持 `limit` 参数，默认 20，最大 100。
   - 字段包含行号、学号、姓名、错误原因。

3. 数据来源
   - 可从错误文件生成时同步保存摘要到任务 resultPayload。
   - 如需长期查询，可新增错误明细表。

4. 权限
   - 只能查看自己的导入任务错误。
   - 管理员可按规则查看全部任务。

### 验收标准

- 导入失败任务详情返回错误摘要。
- 调用预览接口能看到前 N 行错误。
- 越权查看错误预览返回 404。
- 错误文件下载能力仍保持不变。

### 完成记录

- `StudentImportTaskResult` 新增 `errorSummary` 和 `errorPreviewRows`，导入校验失败时写入任务 resultPayload。
- 导入任务详情 `ImportTaskResponse` 返回错误摘要和预览行，用户可先看摘要再决定是否下载错误 Excel。
- 新增 `GET /api/excel/import/{taskId}/errors?limit=20`，默认返回 20 行，最大 100 行。
- 预览接口复用任务 owner 过滤，非本人任务返回 404。
- 单元测试覆盖错误摘要写入、预览行 limit 裁剪和 Controller 预览接口。

---

## 27. 文件上传测试页增强

状态：DONE

### 目标

增强 `/file-upload-test.html`，让它成为文件中心联调和演示页面，覆盖普通上传、秒传、直传、分片上传、断点查询、取消和重试。

### 背景

当前静态测试页已经能验证基本上传链路，但状态展示、失败重试和分片进度还比较轻。文件中心是一个很适合展示工程能力的模块，可以把测试页做成更完整的调试工具。

### 需求范围

1. 基础体验
   - 可配置后端 Base URL。
   - 展示当前文件名、大小、MD5、分片大小。
   - 展示每一步 API 请求和响应摘要。

2. 分片上传
   - 显示每个分片状态。
   - 支持失败分片重试。
   - 支持断点查询后继续上传。
   - 支持取消上传任务。

### 完成记录

- `file-upload-test.html` 新增分片状态表，展示每个分片的大小、状态和失败原因。
- 新增 `继续分片` 和 `重试失败分片` 按钮，支持基于 `/api/files/multipart/{uploadId}/parts` 的断点恢复。
- 上传过程会实时更新分片状态，失败分片可单独重试，不必重传全部文件。
- 页面保留 base URL 可配置、请求/响应日志和文件列表刷新能力，适合作为文件中心联调页。

3. 秒传和直传
   - 秒传命中时展示已有文件 ID。
   - 直传完成后展示文件详情和下载链接。

4. 错误处理
   - CORS 错误、后端错误、MinIO 签名 URL 失败分别提示。
   - 日志可复制，便于排查。

### 验收标准

- 使用页面可以完整跑通普通上传、秒传、直传和分片上传。
- 人工中断分片上传后，可以查询断点并继续。
- 失败日志包含接口路径、状态码和错误摘要。
- 页面不写死个人本机路径、真实服务器地址或密钥。

---

## 28. 导入历史版本清理

状态：DONE

### 目标

在导入可见版本切换落地后，定期清理过旧的 `student_record.import_version` 数据，避免每次全量导入都永久保留一份完整历史数据。

### 背景

版本切换让失败导入不会污染当前可见版本，但成功导入会留下历史版本。历史版本可以用于短期排查和回退，但如果长期不清理，百万级导入多跑几轮后表体积会快速增长，影响索引大小、备份时间和查询维护成本。

### 需求范围

1. 配置项
   - `DATA_CLEANUP_IMPORT_VERSION_CLEANUP_ENABLED`：是否启用历史版本清理。
   - `DATA_CLEANUP_IMPORT_VERSION_RETAIN_COUNT`：总保留版本数，默认 `2`，表示当前版本 + 最近 1 个历史版本。

2. 清理规则
   - 永远不删除 `student_import_version_control.current_version` 指向的当前可见版本。
   - 按 `import_version` 倒序保留最近历史版本。
   - 每次按 `DATA_CLEANUP_BATCH_SIZE` 分批删除，避免单次清理事务过大。

3. 多实例协同
   - 复用保留清理任务的 Redis 分布式锁。
   - 多实例同时调度时只允许一个实例执行版本清理。

### 验收标准

- 当前可见版本不会被清理。
- 默认保留当前版本和最近 1 个历史版本。
- 关闭清理开关后不会删除历史版本数据。
- 清理结果日志和 `CleanupResult.importVersionRows` 能看到本轮清理行数。

### 完成记录

- `CleanupProperties` 新增 `importVersionCleanupEnabled` 和 `importVersionRetainCount`。
- `RetentionCleanupService` 新增导入历史版本清理流程，并将结果写入 `CleanupResult.importVersionRows`。
- `StudentMapper` 新增 `deleteExpiredStudentVersions(retainCount, limit)`，排除当前可见版本后分批删除过旧版本。
- README 补充 `DATA_CLEANUP_IMPORT_VERSION_CLEANUP_ENABLED` 和 `DATA_CLEANUP_IMPORT_VERSION_RETAIN_COUNT`。
- 单元测试覆盖启用清理、保留历史版本数换算和关闭清理开关。

---

## 29. 文件上传断点续传完整化

状态：DONE

### 目标

让大文件上传支持真正的断点续传，网络中断、浏览器刷新或分片失败后，可以只重传缺失分片，而不是从头上传。

### 背景

当前文件上传已经具备直传、秒传和分片上传基础能力，但对于弱网环境和大文件场景，用户仍然会遇到：

- 单个分片失败后需要手动重来
- 页面刷新后上传进度丢失
- 已上传分片无法恢复校验
- 完成合并前的状态查询不够完整

### 需求范围

1. 上传会话持久化
   - 保存 `uploadId`、文件名、大小、md5、分片大小、已上传分片列表
   - 上传任务状态支持 `INIT`、`UPLOADING`、`PAUSED`、`COMPLETED`、`ABORTED`

2. 断点恢复
   - 重新打开页面时可根据 `uploadId` 恢复上传进度
   - 仅补传缺失分片
   - 支持客户端主动暂停和继续

3. 幂等控制
   - 同一分片重复上传返回一致结果
   - `complete` 接口重复调用保持幂等

4. 查询能力
   - 查询已上传分片
   - 查询剩余分片
   - 查询任务状态和失败原因

### 验收标准

- 浏览器中断后可以继续上传，不需要从第 1 片重来。
- 分片重复提交不产生脏数据。
- `complete` 重复调用结果一致。
- 断点恢复后可以正确完成合并。

### 完成记录

- 新增 `POST /api/files/multipart/{uploadId}/resume`，可为已存在的分片任务刷新签名地址。
- 文件上传测试页将 multipart 草稿写入 `localStorage`，重新选择同名同内容文件后可自动恢复。
- 恢复失败时会自动降级为重新初始化，避免过期草稿阻塞新上传。

---

## 30. 异步任务查询增强

状态：DONE

### 目标

把现有导入/导出/文件任务统一暴露为更完整的任务中心查询能力，支持用户自助查看任务执行细节和失败原因。

### 背景

当前任务中心已经支持创建、执行、成功、失败、取消、过期和重试，但对“任务为什么慢了”“失败在哪一步”“能不能重试”这类排查信息还不够直观。

### 需求范围

1. 任务列表筛选
   - 按任务类型、状态、时间范围、关键字、失败类型筛选

2. 任务详情增强
   - 展示开始时间、结束时间、耗时、重试次数、失败原因、建议动作
   - 展示当前任务进度和最近一次心跳

3. 任务日志
   - 记录任务关键阶段日志摘要
   - 支持查看最近若干条操作日志

4. 重试记录
   - 显示每次重试的触发人、触发时间和结果

### 验收标准

- 用户可以按状态筛选自己的历史任务。
- 任务详情可以直接定位失败原因。
- 重试记录能追踪每次执行结果。

### 完成记录

- `/api/tasks/page` 新增 `businessKey`、`failureType`、`keyword`、`createdFrom`、`createdTo` 筛选条件。
- 任务类型、状态和失败类型统一做枚举校验，创建时间范围倒挂时返回参数错误。
- `AsyncTaskResponse` 新增 `remainingRetryCount`、`durationMs`、`workerId`、`lastHeartbeatAt`。
- 基于现有任务时间字段合成 `lifecycleEvents`，展示创建、运行、心跳和终态摘要。
- 单元测试覆盖任务详情增强字段和扩展筛选参数传递。

---

## 31. 下载链路统一签名化

状态：DONE

### 目标

统一文件下载方式，尽量让下载请求直达 MinIO 签名 URL，减少应用服务器带宽占用和线程阻塞。

### 背景

当前部分下载仍由应用服务器转发流量。对于大文件或高并发下载，这会把带宽压力和连接数压力压到应用层，不利于后续扩展。

### 需求范围

1. 统一下载策略
   - 普通文件、导出文件、错误明细文件都支持签名下载
   - 仅保留权限校验和签名生成逻辑

2. 签名有效期
   - 支持短期有效签名 URL
   - 支持过期后重新获取

3. 访问审计
   - 记录文件被谁下载、何时下载、下载来源任务

### 验收标准

- 下载请求不再依赖应用服务器转发大文件内容。
- 过期签名会被正确拒绝。
- 仍保留必要的权限控制和归属校验。

### 完成记录

- 导出文件下载、导入错误明细下载和通用文件下载均返回 HTTP `302 Found`，`Location` 指向 MinIO 短期签名 URL。
- 下载前仍由应用服务校验任务或文件归属，签名 URL 只在校验通过后生成。
- 新增 `download_audit_record` 审计表、MyBatis Mapper、Flyway V6 迁移和本地初始化 SQL。
- 新增 `DownloadAuditService`，记录 `ownerId`、资源类型、资源 ID、对象 Key、文件名、请求 IP、User-Agent 和下载时间。
- 审计写入失败只记录 warn，不影响签名 URL 返回，避免审计库异常阻断下载。

---

## 32. 导入预检模式

状态：DONE

### 目标

在真正创建导入任务前，先对文件做快速预检，提前发现格式、容量和字段问题，减少无效异步任务。

### 背景

当前导入虽然已经有完整校验和错误明细文件，但用户仍然需要先提交任务、等待后台解析后才能看到错误。预检模式可以把明显错误前移。

### 需求范围

1. 文件结构校验
   - 检查扩展名、zip 结构、xlsx 必要文件

2. 容量校验
   - 检查行数、文件大小、列数是否超过阈值

3. 字段预校验
   - 扫描必填、长度和明显非法格式

4. 预检返回
   - 返回错误摘要
   - 可选返回前 N 行问题预览

### 验收标准

- 明显非法文件在提交阶段直接拦截。
- 用户不用等待后台任务就能知道主要错误。
- 合法文件仍可继续进入正式导入流程。

### 完成记录

- 新增 `/api/excel/import/precheck`，不创建导入任务、不上传 MinIO，只做快速预检。
- 预检复用 `.xlsx` 后缀、zip 结构和行数上限扫描逻辑，先拦截明显非法文件。
- 预检再读取前 100 行做字段问题预览，返回必填、长度、邮箱格式和预览范围内重复学号问题。
- 返回结果包含文件大小、数据行数、错误摘要和错误预览行，便于用户在正式提交前修正。
- 单元测试覆盖预检成功、非法文件拦截和前端 Controller 返回路径。

---

## 33. 文件配额与生命周期

状态：DONE

### 目标

给上传中心增加更明确的资源治理能力，避免文件长期堆积和单用户无限制占用存储。

### 背景

随着文件上传中心、导入源文件、错误文件和导出文件都进入对象存储，后续需要统一治理配额、保留时间和自动清理策略。

### 需求范围

1. 配额控制
   - 单用户上传总量限制
   - 单文件大小限制
   - 单日上传次数限制

2. 生命周期
   - 按文件类型设置保留天数
   - 支持任务文件、临时文件、错误文件分类清理

3. 超限处理
   - 超限时返回明确错误码
   - 保留可观测日志和告警信息

### 验收标准

- 超过配额时上传会被明确拒绝。
- 临时和过期文件会按策略自动清理。
- 不影响正常的业务文件访问。

### 完成记录

- `FileCenterProperties` 新增 `maxFileSizeBytes`、`maxTotalStorageBytesPerOwner`、`maxActiveUploadTasksPerOwner`、`maxDailyUploadCountPerOwner`。
- 普通后端上传、直传初始化、分片初始化和直传/分片完成阶段均执行配额校验；秒传命中已有文件不新增占用。
- `FileRecordMapper` 新增正常文件总大小、当天上传数量统计。
- `FileUploadTaskMapper` 新增上传中任务数量、上传中任务预占用空间、过期上传中任务查询和按 id 删除。
- `RetentionCleanupService` 会清理过期未完成的直传/分片任务，并删除目标对象和分片对象，避免临时对象长期堆积。
- README 补充配额环境变量、生命周期清理边界和初始化表说明。
- 单元测试覆盖单文件超限、用户总存储超限、活跃上传任务超限和过期分片任务清理。

---

## 34. 标准 Docker 镜像与运行规范

状态：DONE

编号：DEP-01

优先级：P1

### 目标

把当前 jar 部署方式固化为可复用、可排查、可交接的生产运行规范。

### 需求范围

1. 镜像运行方式
   - 明确使用 JDK/JRE 8 基础镜像。
   - 明确 `/app` 工作目录、jar 文件路径、日志目录和临时目录。
   - 明确 jar 挂载方式，避免路径不一致导致 `Invalid or corrupt jarfile`。

2. JVM 参数
   - 支持 `JAVA_OPTS` 配置堆大小、GC、时区和编码。
   - 默认参数适配 2C4G 小规格服务器。

3. 健康检查
   - 通过 `/actuator/health` 判断启动成功。
   - 容器启动失败时能通过日志快速定位配置缺失、数据库不可达、MinIO 不可达等问题。

### 验收标准

- docker 启动后应用可以稳定访问 `/actuator/health`。
- README 或部署文档能清楚说明 jar 放置位置、启动命令和常见错误。
- 不把生产密码、Token、AccessKey 写入仓库。

### 完成记录

- 新增 `deploy/easyexcel-demo.env.example`，整理应用、MySQL、Redis、MinIO、导入导出、任务中心、文件中心和清理任务的生产环境变量模板。
- 新增 `deploy/docker-compose.easyexcel-demo.yml`，约定 `/app` 工作目录、`/app/easyexcel-demo.jar` jar 挂载路径、日志目录、临时目录、JVM 参数和健康检查。
- 新增 [deployment-runbook.md](deployment-runbook.md)，明确 jar 构建、服务器目录、上传替换、日志查看、健康检查和常见错误排查。
- `.gitignore` 增加 `deploy/*.env`，避免生产环境变量文件误提交；仅允许提交 `*.env.example`。
- README 增加生产部署入口，说明 compose 覆盖文件和同网络服务名访问方式。

---

## 35. docker-compose 部署与回滚流程

状态：DONE

编号：DEP-02

优先级：P1

### 目标

在已有中间件 compose 文件中增加应用服务，并形成一套明确的部署、验证和回滚流程。

### 需求范围

1. compose 服务
   - 增加应用服务、端口映射、依赖服务、健康检查和重启策略。
   - 明确应用与 MySQL、Redis、MinIO 在同一 Docker 网络内访问时使用服务名。

2. 发布流程
   - 上传新 jar。
   - 保留旧 jar 备份。
   - 重启应用服务。
   - 查看日志和健康检查。

3. 回滚流程
   - 停止应用。
   - 切回旧 jar。
   - 重启并验证。

### 验收标准

- 使用一组命令可以完成部署。
- 应用启动失败时不会影响 MySQL、Redis、MinIO 容器。
- 回滚步骤可在 5 分钟内执行完成。

### 完成记录

- `deploy/docker-compose.easyexcel-demo.yml` 可通过 `docker compose -f docker-compose-software.yml -f deploy/docker-compose.easyexcel-demo.yml --env-file deploy/easyexcel-demo.env up -d easyexcel-demo` 与已有中间件 compose 叠加启动。
- compose 服务只新增 `easyexcel-demo`，依赖已有 `mysql`、`redis`、`minio` 服务，不要求重建中间件。
- Runbook 补齐发布步骤：上传 `.jar.new`、备份旧 jar、替换当前 jar、启动应用、查看日志和健康检查。
- Runbook 补齐回滚步骤：备份问题 jar、恢复指定备份、`--force-recreate` 重建应用容器并验证 `/actuator/health`。
- 常见问题表覆盖 `Invalid or corrupt jarfile`、未叠加 compose 文件、内外 MinIO 地址混淆、Docker daemon/context 不一致和 healthcheck 失败。

---

## 36. 用户表、登录接口和 JWT 鉴权

状态：DONE

编号：AUTH-01

优先级：P1

### 目标

用真实用户体系替代 demo token，为后续管理端、审计和多用户隔离打基础。

### 需求范围

1. 数据模型
   - 新增用户表、角色表或用户角色字段。
   - 密码使用 BCrypt 或项目统一加密方式存储。
   - 包含启用/禁用、创建时间、更新时间字段。

2. 登录接口
   - 用户名密码登录。
   - 返回 JWT access token。
   - 支持 token 过期时间配置。

3. 鉴权拦截
   - 从 Bearer token 解析当前用户。
   - 写入 `UserContextHolder`。
   - 保留 demo 模式作为本地开发开关。

### 验收标准

- 未登录访问受保护接口返回 401。
- 普通用户登录后只能访问自己的任务、文件和审计数据。
- 密码不会明文存储或写入日志。

### 完成记录

- 新增 `security_user` 用户表、MyBatis Mapper、Flyway V10 迁移脚本和本地初始化 SQL。
- 新增 `/api/auth/login` 和 `/api/auth/refresh`，登录后返回 Bearer access token 与 refresh token。
- 密码使用 PBKDF2 + 随机盐存储，登录校验对损坏哈希返回认证失败，不暴露底层异常。
- JWT 使用 HS256 签名，支持 access token 和 refresh token 独立过期时间配置。
- `UserContextInterceptor` 优先解析 JWT，demo token 继续作为本地开发兼容模式。
- 支持可配置 bootstrap admin，用于首次初始化管理员账号；密码不写入日志。
- 新增 `PasswordServiceTest`、`JwtTokenServiceTest`、`AuthServiceImplTest`，覆盖哈希校验、token 解析、登录失败、刷新和初始化管理员。

---

## 37. 角色权限模型和管理接口保护

状态：DONE

编号：AUTH-02

优先级：P1

### 目标

让管理端接口从“代码中手动判断 admin”升级为统一权限模型。

### 需求范围

1. 权限模型
   - 定义 `USER`、`ADMIN` 等基础角色。
   - 后续可扩展资源权限码。

2. 权限校验
   - 管理接口统一校验管理员角色。
   - 普通接口统一校验当前用户归属。

3. 审计
   - 管理员执行重试、忽略、删除等操作时记录操作者。

### 验收标准

- 普通用户访问补偿管理接口返回 403。
- 管理员可访问管理接口。
- 权限失败响应使用 `SECURITY_FORBIDDEN`。

### 完成记录

- 新增 `SecurityRoles` 统一定义 `USER`、`ADMIN` 基础角色，收敛权限判断中的散落字符串。
- `PermissionService.requireAdmin()` 作为管理端统一入口，补偿管理接口继续统一校验管理员角色。
- 下载审计查询保持 owner 隔离：普通用户只能查询自己的审计记录，管理员可按 `ownerId` 过滤。
- `/api/tasks/metrics/thread-pools` 改为管理员接口，避免普通用户访问全局线程池运行数据。
- 新增 `TaskCenterControllerTest` 覆盖普通用户无权访问线程池监控和管理员可查看快照。

---

## 38. 运维聚合查询接口

状态：DONE

编号：OPS-01

优先级：P1

### 目标

为后续管理页面提供一个低成本的后端聚合入口，减少前端拼多个接口的复杂度。

### 需求范围

1. 首页摘要
   - 今日任务数、失败任务数、运行中任务数。
   - 补偿积压数。
   - 文件上传数量和存储量。

2. 运行状态
   - 线程池快照。
   - 最近失败任务。
   - 最近补偿记录。

3. 查询隔离
   - 仅管理员可访问全局聚合数据。

### 验收标准

- 一个接口可以返回管理后台首页需要的核心摘要。
- 聚合查询不会扫描大表全量数据。
- 接口具备单元测试或 Mapper 测试覆盖。

### 完成记录

- 新增 `/api/admin/ops/overview` 运维首页聚合接口，统一返回今日任务数、今日失败任务数、运行中任务数、补偿积压、今日文件上传数、总存储量、线程池快照、最近失败任务和最近补偿记录。
- Controller 只负责管理员权限校验和调用 Service，聚合逻辑放在 `OpsDashboardServiceImpl`。
- 任务、补偿、文件 Mapper 新增按时间或状态的聚合查询和最近记录查询，避免在应用侧全量扫描大表。
- 全局运维接口统一使用 `PermissionService.requireAdmin()`，普通用户不能查看全局运行数据。
- 新增 `OpsDashboardServiceImplTest` 和 `OpsDashboardControllerTest`，覆盖聚合值转换、最近记录限制、线程池快照和管理员权限校验。

---

## 39. 轻量管理页面

状态：DONE

编号：OPS-02

优先级：P2

### 目标

提供一个可以直接操作任务中心和补偿中心的轻量前端页面，方便演示和联调。

### 需求范围

1. 任务运维
   - 任务列表、详情、事件日志。
   - 取消、重试。

2. 补偿运维
   - 补偿列表。
   - 手动重试、忽略。

3. 审计与指标
   - 下载审计查询。
   - 线程池状态展示。

### 验收标准

- 本地启动应用后可以打开页面操作任务和补偿。
- 页面不暴露密钥、Token 或真实服务器地址。
- 失败操作能展示统一错误响应。

### 完成记录

- 新增 `src/main/resources/static/ops-dashboard.html`，应用启动后可通过 `/ops-dashboard.html` 访问。
- 页面支持配置后端 baseURL 和 Bearer Token，配置只保存在浏览器 localStorage，不写入项目文件。
- 概览页调用 `/api/admin/ops/overview`，展示今日任务、失败任务、运行中任务、补偿积压、上传数量、文件容量和线程池状态。
- 任务页调用 `/api/tasks/page`，支持按任务类型和状态筛选，并提供任务重试、取消按钮。
- 补偿页调用 `/api/admin/compensations/page`，支持按状态筛选，并提供补偿重试、忽略按钮。
- 审计页调用 `/api/download-audits/page`，展示最近下载审计记录。
- 页面统一识别 `ApiResponse` 包装，失败时展示后端错误消息。

---

## 40. 导入模式策略

状态：DONE

编号：IMP-01

优先级：P1

### 目标

把当前“整体发布新版本”的导入能力扩展为可选择的业务导入模式。

### 需求范围

1. 导入模式
   - `OVERWRITE`：全量覆盖发布新可见版本，旧版本继续保留到生命周期清理。
   - `APPEND`：在当前可见版本内按 `student_no` upsert，文件外已有数据保留。
   - `VALIDATE_ONLY`：只校验不落库，成功时 `importedCount=0`、`validatedCount=文件数据行数`。

2. 接口参数
   - 导入提交支持传入模式。
   - 任务 payload 记录导入模式。

3. 冲突处理
   - 明确重复学号、不存在记录、字段为空时各模式的处理规则。

### 验收标准

- 每种模式都有成功和失败测试用例。
- `VALIDATE_ONLY` 不修改正式表数据。
- 冲突错误能生成明确错误明细。

### 完成记录

- `/api/excel/import` 新增可选 `mode` 参数，支持 `OVERWRITE`、`APPEND`、`VALIDATE_ONLY`，默认保持 `OVERWRITE` 兼容旧调用。
- 幂等指纹纳入导入模式，同一个文件用不同模式提交不会误复用旧任务结果。
- 导入任务 payload 保存 `importMode`，后台任务重试或恢复时按原模式执行。
- `OVERWRITE` 沿用当前全量原子发布：暂存校验通过后写入新 `import_version`，最后 CAS 发布可见版本。
- `APPEND` 使用当前可见版本分块 upsert，文件内重复学号失败，当前版本同学号按新文件内容更新。
- `VALIDATE_ONLY` 只写暂存表并执行完整校验，校验通过后不写正式表，任务结果返回 `validatedCount`。
- `ImportTaskResponse` 返回 `importedCount`、`validatedCount` 和 `importMode`，方便前端区分“校验成功但未落库”。
- 单元测试覆盖 `APPEND` 不切版本、`VALIDATE_ONLY` 不写正式表、任务 payload 模式透传和 Controller 模式参数透传。

---

## 41. 导入字段规则配置化

状态：DONE

编号：IMP-02

优先级：P2

### 目标

让导入校验从写死在代码中逐步升级为可配置规则，便于扩展不同业务表。

### 需求范围

1. 规则模型
   - 字段名、是否必填、最大长度、正则、枚举值、唯一性。

2. 规则应用
   - 预检和正式导入使用同一套规则。
   - 错误明细返回规则命中的具体字段和原因。

3. 默认规则
   - 先内置学生表规则。
   - 后续可迁移到数据库配置。

### 验收标准

- 调整规则后预检和正式导入行为一致。
- 错误明细能定位到字段级问题。
- 不破坏现有学生导入兼容性。

### 完成记录

- 新增 `StudentImportValidationProperties`、`StudentImportRowView`、`StudentImportValidationService` 和 `StudentImportValidationServiceImpl`，把导入规则集中到配置化模型中。
- `application.yml` 为学生导入补齐默认字段规则，预检与正式导入共用同一套规则。
- 预检阶段使用统一校验服务生成错误预览，正式导入阶段也通过同一服务校验暂存数据。
- 学号唯一性预览校验改为通用唯一字段映射，后续扩字段时不用再写死学号。

---

## 42. 文件业务归属、标签和引用关系

状态：DONE

编号：FILE-01

优先级：P2

### 目标

让文件中心从“存文件”升级为“管理业务文件资产”。

### 需求范围

1. 业务归属
   - 文件记录支持 `bizType`、`bizId`。
   - 支持按业务对象查询文件列表。

2. 标签
   - 支持文件标签或分类。
   - 支持按标签筛选。

3. 引用关系
   - 增加引用计数或引用表。
   - 被业务引用的文件不能直接物理清理。

### 验收标准

- 同一业务对象可以关联多个文件。
- 删除文件时能识别是否仍被引用。
- 文件分页支持业务归属过滤。

### 完成记录

- `file_record` 增加 `biz_type`、`biz_id`、`tags`、`reference_count` 字段，并新增 `file_reference` 引用表。
- 文件分页支持业务类型、业务 ID、标签筛选。
- 文件详情响应返回业务归属、标签和引用计数。
- 新增文件元数据绑定接口、引用增加接口、引用移除接口。
- 删除逻辑增加引用保护，引用计数大于 0 或引用表仍有记录时拒绝删除。

---

## 43. 自动补偿执行器

状态：DONE

编号：CON-05

优先级：P1

### 目标

让补偿记录从“可查、可手动处理”升级为“可自动重试、可人工兜底”。

### 需求范围

1. 调度执行
   - 定时扫描 `PENDING` 补偿。
   - 使用分布式锁避免多实例重复处理。

2. 退避策略
   - 支持最大重试次数。
   - 支持按失败次数计算下一次重试时间。

3. 处理器模型
   - 按 `bizType + failureType` 路由到具体补偿处理器。
   - 未知类型保持人工处理。

### 验收标准

- 可重试补偿会自动推进到 `SUCCESS` 或 `FAILED`。
- 超过最大次数后不再无限重试。
- 每次补偿执行都有日志和指标。

### 完成记录

- 新增 `CompensationAutoExecutor`，定时扫描到期的 `PENDING/FAILED` 补偿记录，批量执行自动补偿。
- 自动执行器使用 Redis 分布式锁保护，避免多实例同时处理同一批补偿。
- `compensation_record` Mapper 增加到期扫描、带退避失败更新和终态失败更新；超过最大重试次数后不再继续自动调度。
- 新增 `CompensationHandler` 处理器模型，按补偿记录内容判断是否支持自动处理；未知类型会标记为需要人工处理。
- 新增 `ObjectCleanupCompensationHandler`，自动处理 `ORPHAN_OBJECT` 和 `CLEANUP_OBJECT_FAILED` 中携带 `objectKey` 的 MinIO 对象清理补偿。
- 新增自动补偿配置：`COMPENSATION_AUTO_EXECUTE_*` 与 `COMPENSATION_RETRY_BACKOFF_*`，部署 env 模板已同步。
- `TaskMetricsService` 增加 `demo.compensation.auto.execution.total` 指标，记录 success、failed、unsupported、skipped 等自动补偿结果。
- 单元测试覆盖锁占用跳过、成功执行、无处理器转人工、失败退避、达到最大次数停止重试和对象清理处理器路由。

---

## 44. Grafana Dashboard 和告警规则

状态：DONE

编号：OBS-05

优先级：P1

### 目标

把已有 Prometheus 指标转化为可观察、可告警、可排障的运维能力。

### 需求范围

1. Dashboard
   - 任务提交量、成功率、失败率。
   - 导入/导出耗时和行速率。
   - 线程池活跃数、队列长度、拒绝次数。
   - MinIO 上传耗时。
   - 补偿积压。

2. 告警规则
   - 任务失败率过高。
   - 线程池持续满载。
   - 补偿积压持续增长。
   - MinIO 上传耗时异常。

3. 排障说明
   - 每个告警给出排查步骤。

### 验收标准

- Prometheus 能抓取应用指标。
- Grafana 面板能展示关键趋势。
- 告警规则文档化，可复制到 Prometheus/Alertmanager。

### 完成记录

- 新增 `deploy/prometheus/easyexcel-demo-alerts.yml`，覆盖任务失败率、线程池队列、线程池拒绝、Hikari pending、MinIO 上传慢、补偿积压和 JVM 堆使用率告警。
- 新增 `deploy/grafana/easyexcel-demo-dashboard.json`，覆盖任务吞吐、失败率、导入导出行速率、任务耗时 P95、线程池压力、MinIO 上传耗时、补偿积压和 JVM/CPU 使用率。
- `docs/monitoring-alerting.md` 补充运维资产路径、Prometheus `rule_files` 示例，以及 MinIO 上传慢、补偿积压排障路径。

---

## 45. 300 万 / 500 万数据量压测矩阵

状态：TODO

编号：PERF-01

优先级：P1

### 目标

验证当前架构在更大数据量和并发场景下的边界，形成明确容量结论。

### 需求范围

1. 导出压测
   - 300 万、500 万 CSV 导出。
   - 300 万、500 万 ZIP 分片 CSV 导出。
   - 不建议 XLSX 单 Sheet 超过 Excel 上限。

2. 导入压测
   - 10 万、50 万、100 万导入基线。
   - 并发导入和导出混合压力。

3. 参数矩阵
   - `EXPORT_PAGE_SIZE`
   - `IMPORT_BATCH_SIZE`
   - `IMPORT_WORKER_COUNT`
   - Hikari 最大连接数
   - MinIO 上传耗时

### 验收标准

- 输出性能报告，包含吞吐、耗时、CPU、内存、磁盘、数据库连接和失败原因。
- 给出当前服务器规格下的推荐配置。
- 明确不建议使用的参数组合。

---

## 建议实施顺序

1. `OBS-05`：补齐 Grafana Dashboard 和告警规则，让已有指标真正可用。
2. `IMP-01`：完成导入模式策略，这是剩余 P1 中业务价值最高的一项。
3. `OPS-02`：补齐轻量管理页面，让任务、补偿、审计可视化。
4. `PERF-01`：在部署和观测稳定后再做 300 万 / 500 万压测，结论更可信。
