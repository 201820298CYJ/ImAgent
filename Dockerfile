# =========================================================
# 构建阶段：同时打包 MCP 工具子模块 + 主后端
# =========================================================
FROM maven:3.9-amazoncorretto-21 AS builder
WORKDIR /build

# 1) 先打 MCP 工具服务（独立 Maven 项目）
COPY yu-agent-tools-mcp-server ./yu-agent-tools-mcp-server
RUN cd yu-agent-tools-mcp-server && mvn clean package -DskipTests -q

# 2) 再打主后端
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -q

# =========================================================
# 运行阶段：只带 JRE + 两个 jar
# mcp-servers.json 里写死的相对路径为
#   yu-agent-tools-mcp-server/target/yu-agent-tools-mcp-server-0.0.1-SNAPSHOT.jar
# 所以 MCP jar 必须放在 /app/yu-agent-tools-mcp-server/target/ 下
# =========================================================
FROM amazoncorretto:21
WORKDIR /app

COPY --from=builder /build/target/yu-ai-agent-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=builder /build/yu-agent-tools-mcp-server/target/yu-agent-tools-mcp-server-0.0.1-SNAPSHOT.jar \
     /app/yu-agent-tools-mcp-server/target/yu-agent-tools-mcp-server-0.0.1-SNAPSHOT.jar

EXPOSE 8123

CMD ["java", "-jar", "/app/app.jar"]
