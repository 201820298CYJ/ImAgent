# Docker 部署知识梳理

## 一、Docker 核心概念

| 概念 | 类比 | 说明 |
|------|------|------|
| **镜像 (Image)** | 安装光盘 | 一个只读的模板，包含运行应用所需的一切（代码、依赖、配置） |
| **容器 (Container)** | 运行中的虚拟机 | 镜像的运行实例，彼此隔离，有自己的网络和文件系统 |
| **Dockerfile** | 安装脚本 | 描述如何一步步构建镜像的文本文件 |
| **docker-compose.yml** | 编排清单 | 定义多个容器如何一起启动、互相通信 |
| **Volume（数据卷）** | 外挂硬盘 | 容器销毁后数据仍然保留的持久化存储 |
| **Registry（仓库）** | 应用商店 | 存放和分发镜像的地方，如 Docker Hub |

---

## 二、Dockerfile 和 docker-compose.yml 的关系

**一句话总结**：Dockerfile 负责"怎么造一个镜像"，docker-compose.yml 负责"怎么把多个镜像一起跑起来"。

### 2.1 各自的职责

```
Dockerfile           →  造镜像（单个应用的打包过程）
docker-compose.yml   →  编排容器（多个应用的启动 + 配置 + 网络 + 依赖）
```

| | Dockerfile | docker-compose.yml |
|---|---|---|
| 解决的问题 | 把代码变成可运行的镜像 | 把多个镜像组合成完整系统 |
| 作用范围 | 单个服务 | 整个项目（所有服务） |
| 关注点 | 装什么依赖、复制什么文件、怎么启动 | 谁先启动、端口怎么映射、数据怎么存、网络怎么通 |
| 类比 | 一道菜的做法（食谱） | 一桌宴席的上菜顺序和摆盘 |

### 2.2 它们是怎么关联的

docker-compose.yml 通过 `build` 字段指向 Dockerfile：

```yaml
# docker-compose.yml 中
backend:
  build:
    context: .              # ← 告诉 Docker 去哪里找文件
    dockerfile: Dockerfile  # ← 告诉 Docker 用哪个 Dockerfile 来构建镜像
  image: joeychen123/yu-ai-agent-backend:latest  # ← 构建出来的镜像叫什么名
```

执行 `docker compose up --build` 时的流程：

```
docker-compose.yml 读取配置
        │
        ├── backend 服务 → 找到 build.dockerfile → 执行 ./Dockerfile → 得到后端镜像 → 启动容器
        ├── frontend 服务 → 找到 build.dockerfile → 执行 ./yu-ai-agent-frontend/Dockerfile → 得到前端镜像 → 启动容器
        ├── elasticsearch → 找到 build.dockerfile → 执行 ./docker/elasticsearch/Dockerfile → 得到 ES 镜像 → 启动容器
        ├── postgres 服务 → 没有 build，直接用 image: pgvector/pgvector:pg16 → 拉取现成镜像 → 启动容器
        └── redis 服务   → 没有 build，直接用 image: redis:7-alpine → 拉取现成镜像 → 启动容器
```

### 2.3 不是所有服务都需要 Dockerfile

以本项目为例：

| 服务 | 有 Dockerfile？ | 原因 |
|------|:---:|------|
| backend | ✅ | 需要编译 Java 源码，打包 jar |
| frontend | ✅ | 需要 npm build，再放进 Nginx |
| elasticsearch | ✅ | 需要在官方镜像上加装 IK 分词插件 |
| postgres | ❌ | 官方镜像直接用，不需要改造 |
| redis | ❌ | 官方镜像直接用，不需要改造 |

**规律**：如果官方镜像就能满足需求，不需要写 Dockerfile，compose 里直接写 `image: xxx` 就行。需要在官方镜像基础上做定制（装插件、编译代码），才需要写 Dockerfile。

### 2.4 没有 docker-compose，也能跑

docker-compose 只是"方便工具"。没有它，你也可以手动一个个启动：

```bash
# 手动方式（繁琐，不推荐）
docker run -d --name yu-postgres -p 5432:5432 -e POSTGRES_DB=yu_ai_agent pgvector/pgvector:pg16
docker run -d --name yu-redis -p 6379:6379 redis:7-alpine
docker run -d --name yu-elasticsearch -p 9200:9200 joeychen123/yu-ai-agent-es:latest
docker run -d --name yu-ai-agent-backend -p 8123:8123 joeychen123/yu-ai-agent-backend:latest
docker run -d --name yu-ai-agent-frontend -p 80:80 joeychen123/yu-ai-agent-frontend:latest

# docker-compose 方式（一条命令搞定，还自动处理网络、依赖、健康检查）
docker compose up -d
```

docker-compose 的价值在于：**自动创建内部网络、管理启动顺序、统一配置环境变量、一键启停**。5 个容器手动管就很烦了，compose 让它变成一条命令的事。

---

## 三、本项目的架构总览

```
用户浏览器 (80端口)
    │
    ▼
┌─────────────────────────────────────────────────┐
│  frontend 容器 (Nginx)                           │
│  - 托管 Vue 打包后的静态文件                       │
│  - /api/* 请求反向代理到 backend                   │
└─────────────────┬───────────────────────────────┘
                  │ Docker 内网 (http://backend:8123)
                  ▼
┌─────────────────────────────────────────────────┐
│  backend 容器 (Java 21)                          │
│  - Spring Boot 主服务                            │
│  - MCP 工具子模块                                │
└────┬──────────────┬───────────────┬─────────────┘
     │              │               │
     ▼              ▼               ▼
┌─────────┐  ┌───────────┐  ┌───────────┐
│ postgres │  │elasticsearch│  │   redis   │
│ +pgvector│  │  + IK分词  │  │  会话记忆  │
└─────────┘  └───────────┘  └───────────┘
```

所有容器处于同一个 Docker 网络中，通过**服务名**互相访问（如 `http://elasticsearch:9200`），无需 IP。

---

## 四、Dockerfile 逐行解读

### 3.1 后端 Dockerfile（多阶段构建）

```dockerfile
# ===== 第一阶段：构建 =====
FROM maven:3.9-amazoncorretto-21 AS builder   # 基础镜像：带 Maven + JDK21 的构建环境
WORKDIR /build                                 # 设置工作目录

# 先打包 MCP 工具子模块
COPY yu-agent-tools-mcp-server ./yu-agent-tools-mcp-server
RUN cd yu-agent-tools-mcp-server && mvn clean package -DskipTests -q

# 再打包主后端
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -q

# ===== 第二阶段：运行 =====
FROM amazoncorretto:21                         # 基础镜像：只有 JRE，体积小
WORKDIR /app

# 从构建阶段复制产物（只要 jar，不要源码和 Maven）
COPY --from=builder /build/target/yu-ai-agent-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=builder /build/yu-agent-tools-mcp-server/target/yu-agent-tools-mcp-server-0.0.1-SNAPSHOT.jar \
     /app/yu-agent-tools-mcp-server/target/yu-agent-tools-mcp-server-0.0.1-SNAPSHOT.jar

EXPOSE 8123                                    # 声明容器监听 8123 端口
CMD ["java", "-jar", "/app/app.jar"]           # 容器启动时执行的命令
```

**多阶段构建的好处**：构建阶段有完整的 Maven + JDK（~800MB），运行阶段只有 JRE + jar（~300MB），最终镜像小很多。

### 3.2 前端 Dockerfile

```dockerfile
# ===== 第一阶段：构建 =====
FROM node:20-alpine AS build        # Node 环境，alpine 版本更小
WORKDIR /app
COPY . .
RUN npm install                     # 安装依赖
RUN npm run build                   # 打包生成 dist/ 目录

# ===== 第二阶段：运行 =====
FROM nginx:alpine                   # 只需要 Nginx 来托管静态文件
COPY --from=build /app/dist /usr/share/nginx/html   # 复制打包产物
COPY nginx.conf /etc/nginx/conf.d/default.conf      # 自定义 Nginx 配置
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]  # 前台运行 Nginx（容器要求主进程不能退后台）
```

### 3.3 Elasticsearch Dockerfile

```dockerfile
FROM elasticsearch:8.17.0
RUN elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/8.17.0
```

就做了一件事：在官方 ES 镜像基础上预装 IK 中文分词器插件。

---

## 五、docker-compose.yml 逐块解读

### 4.1 服务定义

| 服务 | 镜像 | 作用 | 端口 |
|------|------|------|------|
| `postgres` | pgvector/pgvector:pg16 | 数据库 + 向量存储 | 5432 |
| `elasticsearch` | 自定义（带 IK 分词） | 全文检索 | 9200 |
| `redis` | redis:7-alpine | 会话记忆/缓存 | 6379 |
| `backend` | 自定义（Java） | 主服务 | 8123 |
| `frontend` | 自定义（Nginx） | 前端 + 反代 | 80 |

### 4.2 关键字段说明

```yaml
services:
  backend:
    image: joeychen123/yu-ai-agent-backend:latest   # 镜像名（推到 Docker Hub 时用）
    build:
      context: .              # 构建上下文：docker build 时能访问的文件范围
      dockerfile: Dockerfile  # 指定 Dockerfile 路径
    container_name: yu-ai-agent-backend   # 容器名（方便 docker logs 查看）
    restart: unless-stopped   # 除非手动停止，否则崩溃后自动重启
    ports:
      - "8123:8123"           # 宿主机端口:容器端口
    depends_on:
      postgres:
        condition: service_healthy   # 等 postgres 健康检查通过后才启动
    environment:
      - ES_URI=http://elasticsearch:9200   # 环境变量，容器间通过服务名通信
    volumes:
      - postgres_data:/var/lib/postgresql/data   # 命名卷，数据持久化
```

### 4.3 healthcheck（健康检查）

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U postgres"]  # 检查命令
  interval: 10s    # 每 10 秒检查一次
  timeout: 5s      # 超过 5 秒算失败
  retries: 5       # 连续失败 5 次才标记为 unhealthy
```

作用：让 `depends_on: condition: service_healthy` 生效，确保数据库真正就绪后才启动后端。

### 4.4 Volumes（数据卷）

```yaml
volumes:
  postgres_data:
    driver: local
```

容器被删除重建后，数据库数据仍然保留在宿主机上。

---

## 六、Nginx 反向代理配置

```nginx
# Vue 路由支持（所有前端路径都返回 index.html，由 Vue Router 处理）
location / {
    try_files $uri $uri/ /index.html;
}

# API 代理：/api/* → 后端容器
location ^~ /api/ {
    proxy_pass http://backend:8123/api/;   # "backend" 是 docker-compose 中的服务名
    proxy_buffering off;                   # 关闭缓冲，支持 SSE 流式响应
    proxy_read_timeout 600s;               # AI 生成可能很慢，超时设长
}
```

---

## 七、常用 Docker 命令速查

### 6.1 镜像相关

| 命令 | 作用 |
|------|------|
| `docker build -t myimage:v1 .` | 根据当前目录的 Dockerfile 构建镜像，打标签 `myimage:v1` |
| `docker images` | 列出本地所有镜像 |
| `docker rmi myimage:v1` | 删除指定镜像 |
| `docker pull nginx:alpine` | 从 Docker Hub 拉取镜像 |
| `docker push joeychen123/yu-ai-agent-backend:latest` | 推送镜像到 Docker Hub |

### 6.2 容器相关

| 命令 | 作用 |
|------|------|
| `docker ps` | 查看正在运行的容器 |
| `docker ps -a` | 查看所有容器（包括已停止的） |
| `docker logs yu-ai-agent-backend` | 查看容器日志 |
| `docker logs -f yu-ai-agent-backend` | 实时跟踪日志（类似 tail -f） |
| `docker exec -it yu-ai-agent-backend bash` | 进入容器内部执行命令 |
| `docker stop yu-ai-agent-backend` | 停止容器 |
| `docker rm yu-ai-agent-backend` | 删除已停止的容器 |
| `docker restart yu-ai-agent-backend` | 重启容器 |

### 6.3 docker-compose 相关（在 docker-compose.yml 目录下执行）

| 命令 | 作用 |
|------|------|
| `docker compose up -d` | 后台启动所有服务（首次会自动构建镜像） |
| `docker compose up -d --build` | 重新构建镜像并启动（代码有改动时用） |
| `docker compose down` | 停止并删除所有容器（数据卷保留） |
| `docker compose down -v` | 停止并删除所有容器 + 数据卷（**慎用，数据会丢**） |
| `docker compose ps` | 查看 compose 管理的容器状态 |
| `docker compose logs backend` | 查看某个服务的日志 |
| `docker compose logs -f` | 跟踪所有服务日志 |
| `docker compose pull` | 拉取所有服务的最新镜像 |
| `docker compose restart backend` | 重启某个服务 |

### 6.4 数据卷相关

| 命令 | 作用 |
|------|------|
| `docker volume ls` | 列出所有数据卷 |
| `docker volume inspect postgres_data` | 查看卷的详细信息（存储位置等） |
| `docker volume rm postgres_data` | 删除指定卷（**数据会丢**） |

### 6.5 网络相关

| 命令 | 作用 |
|------|------|
| `docker network ls` | 列出所有网络 |
| `docker network inspect <network_name>` | 查看网络中有哪些容器 |

### 6.6 清理相关

| 命令 | 作用 |
|------|------|
| `docker system prune` | 清理所有停止的容器、未使用的网络、悬空镜像 |
| `docker system prune -a` | 更彻底清理（包括没被容器使用的镜像） |
| `docker system df` | 查看 Docker 占用的磁盘空间 |

---

## 八、本项目的完整部署流程

### 方式一：服务器上从源码构建

```bash
# 1. 克隆代码
git clone https://github.com/yourrepo/yu-ai-agent.git
cd yu-ai-agent

# 2. 一键启动（会自动构建所有镜像）
docker compose up -d --build

# 3. 查看状态，等所有服务变为 healthy
docker compose ps

# 4. 访问 http://服务器IP:80
```

### 方式二：使用已推送的镜像（推荐）

```bash
# 1. 只需要 docker-compose.yml 和 nginx 配置
# 2. 直接拉取并启动
docker compose pull
docker compose up -d

# compose 会从 Docker Hub 拉取：
#   joeychen123/yu-ai-agent-backend:latest
#   joeychen123/yu-ai-agent-frontend:latest
#   joeychen123/yu-ai-agent-es:latest
```

### 更新部署

```bash
# 本地构建新镜像并推送
docker build -t joeychen123/yu-ai-agent-backend:latest .
docker push joeychen123/yu-ai-agent-backend:latest

# 服务器上拉取并重启
docker compose pull backend
docker compose up -d backend
```

---

## 九、常见问题排查

| 问题 | 排查方法 |
|------|----------|
| 容器启动失败 | `docker compose logs backend` 看报错 |
| 后端连不上数据库 | 确认 depends_on + healthcheck 配置正确；检查环境变量 |
| 前端页面白屏 | `docker exec -it yu-ai-agent-frontend ls /usr/share/nginx/html` 看 dist 是否存在 |
| API 请求 502 | 后端没启动完成，看 backend 日志；或 nginx 配置的代理地址错误 |
| 磁盘空间不足 | `docker system df` 看占用，`docker system prune` 清理 |
| 数据丢失 | 是否误用了 `docker compose down -v`？volumes 是否正确挂载？ |

---

## 十、Dockerfile 指令速查表

| 指令 | 作用 | 示例 |
|------|------|------|
| `FROM` | 指定基础镜像 | `FROM node:20-alpine` |
| `WORKDIR` | 设置工作目录（后续命令在此目录执行） | `WORKDIR /app` |
| `COPY` | 复制文件到镜像中 | `COPY . .` |
| `RUN` | 构建时执行命令（安装依赖等） | `RUN npm install` |
| `EXPOSE` | 声明端口（文档作用，不会自动映射） | `EXPOSE 8123` |
| `CMD` | 容器启动时的默认命令 | `CMD ["java", "-jar", "app.jar"]` |
| `ENV` | 设置环境变量 | `ENV NODE_ENV=production` |
| `ARG` | 构建参数（只在构建时有效） | `ARG VERSION=1.0` |
| `COPY --from=` | 从其他构建阶段复制文件 | `COPY --from=builder /app/dist .` |
