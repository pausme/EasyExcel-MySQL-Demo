# 监控面板和告警规则

本文档用于把当前项目已有的 Actuator/Micrometer 指标落到 Prometheus、Grafana 和告警排障流程中。示例中的地址、实例名和环境名均为占位符，部署时按实际环境替换。

## 1. 指标入口

应用默认暴露：

| 入口 | 说明 |
| --- | --- |
| `/actuator/health` | 应用健康检查 |
| `/actuator/metrics` | Spring Boot Actuator 指标查询 |
| `/actuator/prometheus` | Prometheus 抓取入口 |
| `/api/tasks/metrics/thread-pools` | 任务线程池快照，需业务鉴权 |

核心业务指标：

| 指标 | 标签 | 含义 |
| --- | --- | --- |
| `demo_async_task_total` | `taskType`、`outcome` | 异步任务提交和状态流转次数 |
| `demo_async_task_duration_seconds` | `taskType`、`status` | 异步任务执行耗时 |
| `executor_active_threads` | `name` | 导入/导出线程池活跃线程数 |
| `executor_queued_tasks` | `name` | 导入/导出线程池队列长度 |
| `executor_completed_tasks_total` | `name` | 导入/导出线程池完成任务数 |
| `hikaricp_connections_active` | `pool` | Hikari 活跃连接数 |
| `hikaricp_connections_pending` | `pool` | Hikari 等待连接数 |
| `jvm_memory_used_bytes` | `area`、`id` | JVM 内存使用 |
| `process_cpu_usage` | 无 | 应用进程 CPU 使用率 |

## 2. Prometheus 抓取示例

仓库中已经提供可直接复制或挂载的运维资产：

| 文件 | 用途 |
| --- | --- |
| `deploy/prometheus/easyexcel-demo-alerts.yml` | Prometheus 告警规则组 |
| `deploy/grafana/easyexcel-demo-dashboard.json` | Grafana Dashboard 导入文件 |

```yaml
scrape_configs:
  - job_name: easyexcel-demo
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets:
          - <APP_HOST>:<APP_PORT>
        labels:
          app: easyexcel-demo
          env: standard
```

如果应用放在反向代理后面，建议只在内网开放 `/actuator/prometheus`，外网不直接暴露 Actuator。

## 3. Grafana 面板建议

| 面板 | PromQL 示例 | 观察点 |
| --- | --- | --- |
| 任务提交速率 | `sum by (taskType) (rate(demo_async_task_total{outcome="submitted"}[5m]))` | 是否出现突增 |
| 任务失败率 | `sum(rate(demo_async_task_total{outcome="failed"}[5m])) / clamp_min(sum(rate(demo_async_task_total{outcome=~"success|failed"}[5m])), 1)` | 是否超过业务可接受阈值 |
| 导入耗时 P95 | `histogram_quantile(0.95, sum by (le) (rate(demo_async_task_duration_seconds_bucket{taskType="IMPORT"}[10m])))` | 导入耗时是否退化 |
| 导出耗时 P95 | `histogram_quantile(0.95, sum by (le) (rate(demo_async_task_duration_seconds_bucket{taskType="EXPORT"}[10m])))` | 导出耗时是否退化 |
| 线程池队列 | `max by (name) (executor_queued_tasks{name=~"student-.*"})` | 是否持续堆积 |
| Hikari 等待连接 | `max(hikaricp_connections_pending)` | 是否连接池不足或 SQL 卡住 |
| JVM 堆使用率 | `sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"})` | 是否接近 OOM |
| CPU 使用率 | `process_cpu_usage` | 是否 CPU 饱和 |

## 4. 告警规则示例

完整可复制版本见 `deploy/prometheus/easyexcel-demo-alerts.yml`。如果已有 Prometheus 配置，可以把该文件放入 `rule_files` 指定目录，例如：

```yaml
rule_files:
  - /etc/prometheus/rules/easyexcel-demo-alerts.yml
```

```yaml
groups:
  - name: easyexcel-demo-alerts
    rules:
      - alert: EasyExcelTaskFailureRateHigh
        expr: |
          sum(rate(demo_async_task_total{outcome="failed"}[5m]))
          /
          clamp_min(sum(rate(demo_async_task_total{outcome=~"success|failed"}[5m])), 1)
          > 0.2
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: EasyExcel task failure rate is high
          description: More than 20% async tasks failed in the last 10 minutes.

      - alert: EasyExcelExecutorQueueHigh
        expr: max by (name) (executor_queued_tasks{name=~"student-.*"}) > 5
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: EasyExcel executor queue is high
          description: Import/export executor queue remains high. Check task volume and database pressure.

      - alert: EasyExcelDatabaseConnectionPending
        expr: max(hikaricp_connections_pending) > 0
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: EasyExcel has pending database connections
          description: Hikari has pending connection requests. Check slow SQL, import worker count and pool size.

      - alert: EasyExcelHeapUsageHigh
        expr: sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.85
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: EasyExcel heap usage is high
          description: Heap usage is above 85%. Check large imports, export generation and queue backlog.
```

## 5. 排障路径

### 5.1 Task Failure Rate High

1. 查看 `/api/tasks`，按 `status=FAILED` 和 `taskType` 过滤最近任务。
2. 优先看 `failureType`：`VALIDATION_ERROR` 通常是文件或业务数据问题，`DEPENDENCY_ERROR` 重点查 MySQL、Redis、MinIO。
3. 如果同一类错误集中出现，查看服务端日志中的 `taskId` 和 `traceId`。
4. 对可重试任务使用重试接口；对不可重试任务下载错误明细或重新提交修正后的文件。

### 5.2 Thread Pool Queue Keeps Growing

1. 调用 `/api/tasks/metrics/thread-pools` 看 `activeCount`、`queueSize`、`completedTaskCount`。
2. 如果导入 worker 队列高，同时 Hikari pending 高，优先降低 `IMPORT_WORKER_COUNT` 或提升数据库连接池和数据库规格。
3. 如果导出队列高，检查是否有多个百万级导出并发，必要时降低 `TASK_CENTER_MAX_ACTIVE_TASKS_TOTAL`。

### 5.3 Database Connection Waiting

1. 查看 Hikari pending 和 active 连接。
2. 检查慢 SQL、导入暂存表写入、版本合并和导出游标查询。
3. 确认 `IMPORT_WORKER_COUNT * IMPORT_MAX_CONCURRENT_TASKS` 不超过可用数据库连接数。

### 5.4 JVM Heap Usage Is High

1. 确认导入文件大小和行数是否超过默认护栏。
2. 检查是否有多个大导入/大导出同时运行。
3. 优先通过任务限流和容量护栏降压，再考虑增加堆内存或服务器规格。

### 5.5 MinIO Upload Is Slow

1. 先区分 `scene`：`import-source` 慢通常是客户端到应用或应用到 MinIO 链路问题；`export-result` 慢通常是导出文件体积或 MinIO 写入压力。
2. 查看 MinIO 容器日志、磁盘 IO、网络延迟和桶生命周期规则是否异常。
3. 如果只有大文件慢，优先建议导出使用 `ZIP_CSV_PARTS`，并确认应用和 MinIO 部署在同一内网。

### 5.6 Compensation Backlog Is High

1. 管理员调用 `/api/admin/compensations/page`，按 `status=PENDING,FAILED` 查看积压类型。
2. 如果集中是 `ORPHAN_OBJECT` 或 `CLEANUP_OBJECT_FAILED`，重点查 MinIO 权限、桶名、对象前缀和生命周期配置。
3. 如果自动补偿一直失败，查看 `demo_compensation_auto_execution_total` 的 `failureType` 分布，必要时先手动 ignore 无风险记录。
