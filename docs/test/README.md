# 接口扁平化测试文档

本目录以测试工程师视角，对本系统全部 HTTP 接口（4 个模块、32 个接口）进行**扁平化、全流程、全封闭**测试，并记录结果。

## 目录

| 文件 | 说明 |
| --- | --- |
| [接口扁平化测试方案.md](接口扁平化测试方案.md) | 测试目标、范围、策略、环境与通过准则 |
| [接口扁平化测试用例.xlsx](接口扁平化测试用例.xlsx) | 全量用例矩阵（134 条，含鉴权 3 条）+ 接口参数列表 + 执行结果，三个工作表 |
| [测试执行记录.md](测试执行记录.md) | 标准环境权威执行记录（R7）、结果汇总、问题与风险（F-02/F-09/F-10/F-11） |
| live-test-results.json | 联调脚本自动生成的结构化结果，包含真实地址和签名 URL，已加入 `.gitignore` |
| [../performance-report.md](../performance-report.md) | 导入/导出性能压测与调优报告（已完成） |

## 快速复现

```bash
# 1. 单元/切片测试（无需真实中间件）
mvn test

# 2. 启动被测应用（使用真实环境参数，端口自定）
java -DSERVER_PORT='<应用端口>' -jar target/demo-0.0.1-SNAPSHOT.jar

# 3. 执行接口联调测试（应用就绪后；正式鉴权环境需显式传入测试 Token）
BASE_URL='<STANDARD_BASE_URL>' \
API_SECURITY_DEMO_USER_TOKEN='<USER_TOKEN>' \
API_SECURITY_DEMO_ADMIN_TOKEN='<ADMIN_TOKEN>' \
python3 scripts/run_flat_tests.py

# 4. 重新生成用例矩阵
python3 scripts/gen_api_test_cases.py
```

## 用例规模与执行结果

- 用例矩阵：**134 条**（鉴权 3 / Excel 32 / 任务中心 21 / 报表运行控制 30 / 文件上传中心 48），覆盖 32 个接口
- 单元/切片测试：**10 个测试类 / 40 用例全部通过**
- 标准环境权威轮（R7，Bearer 鉴权）：**76 用例 / 72 通过 / 4 失败**；其中 F-02 三项已在当前代码本地回归修复，空库取消竞态和超单 Sheet 边界的脚本误判已加固，见 [测试执行记录.md](测试执行记录.md)
- 性能（标准环境）：1M 导出 **3/3 成功、平均 ~23,300 行/s**；100k 导入 **3/3 成功、平均 ~3,900 行/s**；1M 导入在该硬件不可行（F-11，已加 swap 缓解）——详见 [../performance-report.md](../performance-report.md) §5
- 历史轮次（R1–R6）数据已全部作废
