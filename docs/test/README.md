# 接口扁平化测试文档

本目录以测试工程师视角，对本系统全部 HTTP 接口（4 个模块、32 个接口）进行**扁平化、全流程、全封闭**测试，并记录结果。

## 目录

| 文件 | 说明 |
| --- | --- |
| [接口扁平化测试方案.md](接口扁平化测试方案.md) | 测试目标、范围、策略、环境与通过准则 |
| [接口扁平化测试用例.xlsx](接口扁平化测试用例.xlsx) | 全量用例矩阵（135 条，含鉴权 3 条）+ 接口参数列表 + 执行结果，三个工作表 |
| [测试执行记录.md](测试执行记录.md) | 标准环境当前权威执行记录（R7-R12）、结果汇总、问题与风险（F-02/F-09/F-10/F-11） |
| live-test-results.json | 联调脚本自动生成的结构化结果，包含真实地址和签名 URL，已加入 `.gitignore` |
| [../performance-report.md](../performance-report.md) | 导入/导出性能压测与调优报告（已完成） |

## 快速复现

```bash
# 1. 单元/切片测试（无需真实中间件）
mvn test

# 2. 本地一键集成测试（自动启动 MySQL、Redis、MinIO 和应用；需要本机 Docker）
scripts/run_integration_tests.sh

# 3. 手动启动被测应用（使用真实环境参数，端口自定）
java -DSERVER_PORT='<应用端口>' -jar target/demo-0.0.1-SNAPSHOT.jar

# 4. 手动执行接口联调测试（应用就绪后；正式鉴权环境需显式传入测试 Token）
BASE_URL='<STANDARD_BASE_URL>' \
API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>' \
API_SECURITY_DEMO_ADMIN_TOKEN='<ADMIN_TOKEN>' \
python3 scripts/run_flat_tests.py

# 5. 重新生成用例矩阵
python3 scripts/gen_api_test_cases.py

# 6. 生成本地回归样本
python3 scripts/gen_regression_fixtures.py
```

## 用例规模与执行结果

- 用例矩阵：**135 条**（鉴权 3 / Excel 32 / 任务中心 21 / 报表运行控制 30 / 文件上传中心 49），覆盖 32 个接口
- 单元/切片测试：**30 个测试类 / 176 用例全部通过**
- 本地自动补偿回归（R24，2026-08-22 10:59）：**38 个测试类 / 164 用例全部通过**，新增覆盖自动补偿执行器、退避重试、最大次数转人工、对象清理补偿处理器和自动补偿指标。
- 本地导入模式回归（R26，2026-08-22 11:33）：**38 个测试类 / 166 用例全部通过**，新增覆盖 `APPEND` 不切版本、`VALIDATE_ONLY` 不写正式表、导入模式 payload 和 Controller 参数透传。
- 标准环境全量回归（最终轮 R26，PERF-01 完整矩阵轮，2026-08-22）：**77 用例 / 77 通过 / 0 失败**；单测 176/176——R23~R26 连续四轮全绿，见 [测试执行记录.md](测试执行记录.md)
- 本地权限与运维聚合回归（R23，2026-08-22 10:35）：**36 个测试类 / 155 用例全部通过**，新增覆盖用户登录、JWT 解析、刷新令牌、bootstrap admin、管理员线程池监控保护和运维首页聚合。
- 本地 P2 回归（2026-08-22 09:34）：**30 个测试类 / 138 用例全部通过**，新增覆盖补偿管理、学生查询、下载审计、字段级参数校验、业务指标和线程池拒绝可观测。
- 性能（标准环境终态 R26）：XLSX 1M ~23,300 行/s；**ZIP 分片 CSV 7M 90,257 行/s（3M→7M 零衰减）**；3M 导入 4,757 行/s 成功——详见 [../performance-report.md](../performance-report.md) §5.6
- 历史轮次（R1–R6）数据已全部作废

回归样本生成、命名和清理规则见 [../regression-datasets.md](../regression-datasets.md)。
