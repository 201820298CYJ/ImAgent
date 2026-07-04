# 项目启动 & 部署指南

面向"新克隆下来的人"或"未来的自己"，把本项目从 0 起跑到部署上云的所有关键动作整理清楚。

---

## 1. 项目整体结构

```
yu-ai-agent/
├── src/                            # 主后端（Spring Boot 3.4，Java 21）
├── yu-ai-agent-frontend/           # 前端（Vue 3 + Vite）
├── yu-agent-tools-mcp-server/      # MCP 工具服务（stdio 子进程，被后端拉起）
├── docker/                         # ES/Nginx 的自定义 Dockerfile 与 nginx 配置
├── docker-compose.yml              # 一键起全套基础设施 + 应用
├── Dockerfile                      # 后端镜像
└── study/                          # 学习笔记（本文件所在处）
```

**运行时依赖**：PostgreSQL + pgvector、Elasticsearch（含 IK 分词器）、Redis、MCP 子进程。

---

## 2. 本地开发启动流程（推荐日常用法）

用 Docker 起"重的基础设施"，用 IDE / 命令行起"轻的应用代码"，改代码不用重建镜像。

### 2.1 前置条件

- JDK 21
- Node.js 20+（用于前端）
- Docker Desktop（用于起 PG / ES / Redis）
- 一个可用的 DashScope API Key（写在 `src/main/resources/application.yml` 的 `spring.ai.dashscope.api-key`）

### 2.2 起基础设施

```bash
docker compose up -d postgres elasticsearch redis
```

这只会拉起三个数据类容器，不会碰后端和前端。验证：

```bash
docker ps                                            # 看三个容器状态 healthy
curl -s http://localhost:9200/_cluster/health        # ES 健康
docker exec yu-postgres pg_isready -U postgres       # PG 就绪
docker exec yu-redis redis-cli ping                  # Redis PONG
```

### 2.3 构建 MCP 工具服务 jar

后端启动时会通过 `stdio` 拉起 MCP 子进程（`src/main/resources/mcp-servers.json` 指向 jar 路径）。第一次或改过 MCP 代码要重打：

```bash
cd yu-agent-tools-mcp-server
../mvnw clean package -DskipTests
cd ..
```

产物：`yu-agent-tools-mcp-server/target/yu-agent-tools-mcp-server-0.0.1-SNAPSHOT.jar`。

> 关键坑：MCP 使用 stdio 协议，**stdout 必须只输出 JSON-RPC**。项目里已用 `yu-agent-tools-mcp-server/src/main/resources/logback-spring.xml` 把所有日志重定向到 stderr。改动该 jar 时不要恢复默认日志配置，否则后端会 20s 超时握手失败。

### 2.4 启动后端

```bash
./mvnw spring-boot:run
```

启动约 15–60 秒，看到 `Started YuAiAgentApplication` 且日志里有 `Server response with Protocol: 2024-11-05, Info: Implementation[name=yu-agent-tools-mcp-server ...]` 说明 MCP 也握手成功。

启动过程还会自动执行 `DocumentIndexService.buildIndex()`：加载 `src/main/resources/document/*.md`、切分、DashScope Embedding、写入 pgvector 与 ES。**新增/修改 md 文件必须重启后端**才会生效。

后端监听：`http://localhost:8123/api/`；Swagger：`http://localhost:8123/api/swagger-ui.html`。

### 2.5 启动前端

```bash
cd yu-ai-agent-frontend
npm install         # 首次
npm run dev
```

Vite dev server：`http://localhost:3000/`（内部 axios 直连 `http://localhost:8123/api`）。

> 平台坑：如果之前在别的机器/OS 装过依赖，`node_modules/esbuild` 可能不匹配当前平台，报 `The service was stopped`。修复：`rm -rf node_modules/esbuild node_modules/@esbuild && npm install esbuild --no-save`。

### 2.6 常见清理动作

- **清空知识库重建**（新增文档、或想去重）：

  ```bash
  docker exec -i yu-postgres psql -U postgres -d yu_ai_agent -c "TRUNCATE vector_store;"
  curl -s -X DELETE "http://localhost:9200/knowledge_document"
  ./mvnw spring-boot:run
  ```

- **端口冲突杀进程**：`lsof -ti tcp:8123 | xargs kill -9`

---

## 3. Docker 在本项目中的角色

Docker 在这里承担 **两类完全不同的职责**，别混淆：

### 3.1 类型 A：基础设施容器（开发 & 生产都用）

`postgres` / `elasticsearch` / `redis` 三个服务。它们的价值在于：

- 版本、扩展一致：pgvector 版本 pg16、ES 8.17 + IK 分词器插件（`docker/elasticsearch/Dockerfile` 里预装）、Redis 7-alpine。手工装容易踩版本 / 插件坑。
- 数据持久化：`volumes:` 挂载到命名卷（`postgres_data` / `es_data` / `redis_data`），容器删了数据还在。
- 健康检查：`healthcheck` 保证依赖服务真的就绪，后端启动才不会连不上。

日常开发只用它们就够了：`docker compose up -d postgres elasticsearch redis`。

### 3.2 类型 B：应用容器（只有生产 / 全套演示用）

- `Dockerfile`（根目录）：把主后端打成一个 fat jar 镜像。基于 `maven:3.9-amazoncorretto-21`，在容器里 `mvn clean package`，然后 `java -jar` 运行。
- `yu-ai-agent-frontend/Dockerfile`：**多阶段构建**——先在 `node:20-alpine` 里跑 `npm install + npm run build` 生成 `dist/`，再拷进 `nginx:alpine` 里托管。
- `docker/nginx/nginx-prod.conf`：Nginx 站点配置，前端静态资源 + 把 `/api/` 反向代理到 `backend:8123/api/`（Docker 内网通过 compose 服务名解析）。特别配了 SSE 关键字段：`proxy_buffering off; proxy_read_timeout 600s;`，不然流式返回会被 Nginx 缓存卡住。

一键起全套（真正模拟线上）：

```bash
docker compose up -d --build
```

访问 `http://localhost/`（Nginx 80 端口），前端 → Nginx → 后端 → PG/ES/Redis。

### 3.3 一句话总结

- **写代码时**：只用 A（基础设施），后端前端跑在宿主机，快、能热更、方便断点。
- **想复现生产**：加上 B（应用镜像），整个 docker-compose 起来，等同于服务器上运行的样子。

---

## 4. 部署到云服务器

采用 **"本地打镜像 → 推镜像仓库 → 服务器 pull 即用"** 的路线。
好处：服务器上什么都不装（除了 Docker），也不需要克隆源码 / 装 JDK / Maven / Node；每次更新只推变化的层，服务器几秒钟拉完就能重启。

镜像仓库这里默认用 [Docker Hub](https://hub.docker.com)（免费公共仓库），本项目命名空间为 `joeychen123`；如果嫌 Docker Hub 慢，把命名空间换成 `registry.cn-hangzhou.aliyuncs.com/<你的命名空间>` 走阿里云 ACR，其他步骤一致。

### 4.1 一次性准备（本地）

1. Docker Hub 注册账号（本项目使用 `joeychen123`）。
2. 本地登录：`docker login`（输账号密码；ACR 换成 `docker login registry.cn-hangzhou.aliyuncs.com`）。
3. `docker-compose.yml` 里三个应用服务的 `image:` 名字已用 `joeychen123/...:latest`；换机器 / 换账号时改成新的即可。

### 4.2 本地打包并推送

`docker-compose.yml` 里每个应用服务同时写了 `image:` + `build:`，一条命令搞定：

```bash
# 在项目根目录
docker compose build backend frontend elasticsearch    # 打三个自定义镜像（首次 8~15 分钟，之后只重打改动层）
docker compose push  backend frontend elasticsearch    # 推送到 Docker Hub
```

主要产出三个镜像：

| 镜像 | 内容 |
| ---- | ---- |
| `joeychen123/yu-ai-agent-backend:latest` | JRE21 + 主后端 fat jar + **MCP 工具 jar**（已通过多阶段 Dockerfile 一起打进来） |
| `joeychen123/yu-ai-agent-frontend:latest` | Nginx + `npm run build` 出的 `dist/` 静态资源 |
| `joeychen123/yu-ai-agent-es:latest` | ES 8.17 + IK 中文分词器插件 |

> PG / Redis 用官方镜像，不用自己打。
>
> 关于 API Key：`application.yml` 里的 `spring.ai.dashscope.api-key` 直接写死也可以（本项目就这么用），会被一起打进镜像。仓库如果是**公开**的，注意别把 key 一起推上去 —— 要么改成私有仓库，要么把 key 挪成环境变量。

### 4.3 服务器端准备

服务器（一台 4C8G+ 的 Linux 云主机就够）只需要装 Docker：

```bash
# Ubuntu 22.04 示例
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker

# 云厂商安全组只开放 80（前端 Nginx）；5432/6379/9200 千万别对公网开
```

在服务器上新建目录，只放 **一个文件**：`docker-compose.yml`。可以用 `scp` 从本地传过去，也可以直接 `vim` 粘贴。**不需要项目源码**。

如果你用的是私有镜像仓库，服务器上也 `docker login` 一次。

### 4.4 首次启动

```bash
docker compose pull        # 从仓库拉镜像（首次几分钟，看网速；国内推荐 ACR）
docker compose up -d       # 后台启动全套
docker compose ps          # 看是否都 healthy
docker compose logs -f backend
```

后端首次启动会跑 `DocumentIndexService.buildIndex()`：把 md 文档做 embedding 写入 PG + ES，看到 `Started YuAiAgentApplication` 就 OK。

### 4.5 验收

- 浏览器打开 `http://<公网 IP>/`，应看到聊天界面。
- 发一句 `你好` → 走 CHAT 意图，验证 SSE 流式。
- 发一句 `帮我搜索 Spring AI 官方仓库最新动态` → 走 TASK 意图，验证 MCP 工具能联网。

### 4.6 日常更新

**本地改完代码：**

```bash
docker compose build backend        # 只重打改的服务
docker compose push  backend
```

**服务器上：**

```bash
docker compose pull backend
docker compose up -d backend        # 只重启 backend，其他容器不动
```

整个链路一般 30 秒 –2 分钟就能完成一次上线。

### 4.7 常用运维命令

```bash
# 看日志
docker compose logs -f --tail=200 backend

# 只重启不重拉
docker compose restart backend

# 清空知识库重建（新加了 md 文档时）
docker exec -i yu-postgres psql -U postgres -d yu_ai_agent -c "TRUNCATE vector_store;"
curl -s -X DELETE "http://localhost:9200/knowledge_document"
docker compose restart backend

# 完全销毁重来（含数据卷，慎用）
docker compose down -v
```

### 4.8 上生产的加固清单（面试可扩展说）

1. **HTTPS**：给 Nginx 配 Let's Encrypt 证书，`certbot --nginx` 一键签发。
2. **端口收敛**：`docker-compose.yml` 里 `postgres` / `elasticsearch` / `redis` 的 `ports:` 映射到宿主机的那一段删掉，只走 Docker 内网，杜绝外部直连数据库。
3. **持久化备份**：`postgres_data` / `es_data` 定期 `docker run --rm -v postgres_data:/data alpine tar czf - /data > backup.tgz` 备份到对象存储。
4. **资源限制**：ES 的 `ES_JAVA_OPTS=-Xms512m -Xmx512m` 已定死；后端也可以在 compose 里加 `deploy.resources.limits.memory: 1.5g`。
5. **健康检查上告警**：引入 `spring-boot-starter-actuator`，把 `/api/actuator/health` 挂到 UptimeRobot / 云监控。
6. **CI/CD**：GitHub Actions 打 tag 后自动 `docker build + push`，服务器 Watchtower 监听镜像变更自动 `pull + up`；或简易点，服务器上加一个 `cron: docker compose pull && docker compose up -d`。

---

## 5. 快速排错速查

| 症状 | 可能原因 | 处理 |
| ---- | -------- | ---- |
| 启动报 `expected single matching bean but found 2: dashscopeEmbeddingModel, ollamaEmbeddingModel` | pom 里同时引入了 Ollama starter | 已移除，别再加回去 |
| MCP 20s 超时握手失败 | MCP jar 日志走了 stdout | 检查 `yu-agent-tools-mcp-server/src/main/resources/logback-spring.xml` 是否存在且把 root 指到 `STDERR` |
| Embedding 报 `The input texts limit 25` | DashScope 单次 Embedding 上限 25 | `DocumentIndexService.buildIndex` 已改分批写入，别改回一次性 `add(all)` |
| SSE 前端一次性收到、不是流式 | Nginx 缓存 / 后端用了 `.call()` 而非 `.stream()` | `nginx-prod.conf` 里 `proxy_buffering off` + BaseAgent/ToolCallAgent 用 `.stream().content()` |
| 前端消息被切成好几个气泡 | 老逻辑按标点/长度分气泡 | `SuperAgent.vue` 已改为"首次收到时创建一条空气泡、后续 chunk 追加到同一条 content" |
| Vite dev 起不来报 `The service was stopped` | esbuild 平台二进制不匹配 | `rm -rf node_modules/esbuild node_modules/@esbuild && npm install esbuild --no-save` |
