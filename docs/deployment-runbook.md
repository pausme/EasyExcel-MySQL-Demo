# EasyExcel Demo 部署与回滚手册

本文档用于将 EasyExcel Demo 以 jar 挂载方式部署到已有 Docker 中间件环境中。示例只使用占位符和内网服务名，不包含真实密码、Token、AccessKey 或服务器地址。

## 1. 目录约定

服务器建议使用如下目录结构：

```text
/dev-ops/xfg-dev-tech-docker-install/software
├── docker-compose-software.yml        # 已有 MySQL / Redis / MinIO / Portainer 等中间件
├── apps
│   ├── easyexcel-demo.jar             # 当前运行 jar
│   └── backups                        # 历史 jar 备份
├── deploy
│   ├── docker-compose.easyexcel-demo.yml
│   └── easyexcel-demo.env             # 生产环境变量文件，不提交仓库
├── logs
│   └── easyexcel-demo
└── tmp
    └── easyexcel-demo
```

应用容器内固定约定：

| 项 | 路径 |
| --- | --- |
| 工作目录 | `/app` |
| jar 路径 | `/app/easyexcel-demo.jar` |
| 日志目录 | `/app/logs` |
| 临时目录 | `/app/tmp` |
| 健康检查 | `GET /actuator/health` |

## 2. 构建 jar

本机使用项目约定的 JDK 和 Maven 构建：

```bash
cd /Users/dingli/Code/JavaCode/demo/demo

JAVA_HOME=/Users/dingli/Dependent/JDK/jdk8u482-b08/Contents/Home \
/Users/dingli/Dependent/apache-maven-3.6.3/bin/mvn clean package
```

构建产物：

```text
target/demo-0.0.1-SNAPSHOT.jar
```

## 3. 准备服务器文件

首次部署时，在服务器创建目录：

```bash
cd /dev-ops/xfg-dev-tech-docker-install/software

mkdir -p apps/backups deploy logs/easyexcel-demo tmp/easyexcel-demo
```

复制模板文件：

```bash
# 在本机执行，按实际服务器和路径替换。
scp deploy/docker-compose.easyexcel-demo.yml <user>@<server>:/dev-ops/xfg-dev-tech-docker-install/software/deploy/
scp deploy/easyexcel-demo.env.example <user>@<server>:/dev-ops/xfg-dev-tech-docker-install/software/deploy/easyexcel-demo.env
```

编辑服务器上的 `deploy/easyexcel-demo.env`，替换所有 `<CHANGE_ME>` 和 `<PUBLIC_HOST>`。生产环境不要把该文件提交回仓库。

同一 compose 网络内推荐使用服务名访问中间件：

```text
MYSQL_URL=mysql:3306
REDIS_HOST=redis
REDIS_PORT=6379
MINIO_ENDPOINT=http://minio:9000
```

`MINIO_PUBLIC_ENDPOINT` 用于生成浏览器可访问的签名下载地址，应配置为外部可访问的 MinIO API 地址。

## 4. 上传新版本

```bash
# 本机执行，按实际服务器和路径替换。
scp target/demo-0.0.1-SNAPSHOT.jar <user>@<server>:/dev-ops/xfg-dev-tech-docker-install/software/apps/easyexcel-demo.jar.new
```

服务器执行替换：

```bash
cd /dev-ops/xfg-dev-tech-docker-install/software

if [ -f apps/easyexcel-demo.jar ]; then
  cp apps/easyexcel-demo.jar "apps/backups/easyexcel-demo.jar.$(date +%Y%m%d%H%M%S)"
fi

mv apps/easyexcel-demo.jar.new apps/easyexcel-demo.jar
```

## 5. 启动或更新应用

将应用服务与已有中间件 compose 文件叠加启动：

```bash
cd /dev-ops/xfg-dev-tech-docker-install/software

docker compose \
  -f docker-compose-software.yml \
  -f deploy/docker-compose.easyexcel-demo.yml \
  --env-file deploy/easyexcel-demo.env \
  up -d easyexcel-demo
```

如果已有中间件服务已经运行，以上命令只会创建或更新 `easyexcel-demo`，不会重建 MySQL、Redis、MinIO。若 Docker 提示拉取中间件镜像，通常说明当前 Docker daemon 看不到已有镜像，需先确认 Docker context、socket 和服务是否一致。

## 6. 验证

查看容器状态：

```bash
docker ps --filter name=easyexcel-demo
docker compose \
  -f docker-compose-software.yml \
  -f deploy/docker-compose.easyexcel-demo.yml \
  --env-file deploy/easyexcel-demo.env \
  ps easyexcel-demo
```

查看日志：

```bash
docker logs --tail 200 easyexcel-demo
docker logs -f easyexcel-demo
```

健康检查：

```bash
curl -fsS http://127.0.0.1:18088/actuator/health
```

期望返回：

```json
{"status":"UP"}
```

常用接口烟测：

```bash
curl -fsS http://127.0.0.1:18088/api/excel/count \
  -H "Authorization: Bearer <USER_TOKEN>"
```

## 7. 回滚

列出备份：

```bash
ls -lt apps/backups/easyexcel-demo.jar.*
```

切回指定版本：

```bash
cd /dev-ops/xfg-dev-tech-docker-install/software

cp apps/easyexcel-demo.jar "apps/backups/easyexcel-demo.jar.bad.$(date +%Y%m%d%H%M%S)"
cp apps/backups/easyexcel-demo.jar.<BACKUP_TIMESTAMP> apps/easyexcel-demo.jar

docker compose \
  -f docker-compose-software.yml \
  -f deploy/docker-compose.easyexcel-demo.yml \
  --env-file deploy/easyexcel-demo.env \
  up -d --force-recreate easyexcel-demo

curl -fsS http://127.0.0.1:18088/actuator/health
```

回滚只替换应用 jar，不修改 MySQL、Redis、MinIO 数据。涉及数据库迁移的版本回滚要额外确认 Flyway 迁移是否向后兼容。

## 8. 常见问题

| 现象 | 常见原因 | 处理 |
| --- | --- | --- |
| `Invalid or corrupt jarfile /app/easyexcel-demo.jar` | jar 路径挂载错、上传中断、把 HTML/错误页当 jar 上传 | 检查 `ls -lh apps/easyexcel-demo.jar`，重新上传并替换 |
| `no such service: easyexcel-demo` | 未叠加 `deploy/docker-compose.easyexcel-demo.yml` | 使用两个 `-f` 参数启动 |
| 应用启动后连不上 MySQL | `MYSQL_URL` 使用了公网地址或端口不对 | 同 compose 网络内使用 `mysql:3306` |
| 下载签名 URL 浏览器打不开 | `MINIO_PUBLIC_ENDPOINT` 配成了容器内地址 | 改成浏览器可访问的 MinIO API 地址 |
| 健康检查一直失败 | 应用未启动完成、端口不一致、镜像缺少 `wget` | 先 `curl /actuator/health`；如应用正常但 healthcheck 失败，换带 `wget/curl` 的 JRE 镜像或调整 healthcheck 命令 |
| 线程池频繁拒绝 | 导入/导出并发过高或队列太小 | 观察 `demo.thread.pool.rejected.total`，调低提交速率或调整线程池/队列/数据库连接池 |

## 9. 安全要求

- 不提交 `deploy/easyexcel-demo.env`。
- 不在 README、测试报告、commit message 中写真实密码、Token、AccessKey。
- `MINIO_PUBLIC_ENDPOINT` 可以公开访问时，Bucket 仍应保持私有，下载通过短期签名 URL。
- 生产建议关闭 `API_SECURITY_DEMO_MODE`，后续由真实用户体系替代 demo token。
