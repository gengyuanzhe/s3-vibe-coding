# CLAUDE.md - Jetty S3-Like Storage Service

## 项目概述

基于 Jetty 12 的 S3 风格文件存储服务，提供 REST API 和 Web 管理界面。支持文件上传、下载、桶管理、目录结构等功能。

**项目路径**: `/Users/gengyuanzhe/code/AI/s3-vibe-coding`
**语言**: Java 21
**框架**: Jetty 12.0.15 (EE10)
**Servlet API**: Jakarta Servlet 6.1

## 项目结构

```
s3-vibe-coding/
├── pom.xml                                    # Maven 配置，Jetty EE10 依赖
├── README.md                                  # 用户文档
├── CLAUDE.md                                  # 本文件
├── src/main/
│   ├── java/org/example/
│   │   ├── Main.java                    # Jetty 服务器启动入口
│   │   ├── servlet/S3Servlet.java         # S3 API 处理
│   │   ├── filter/AuthenticationFilter.java  # 身份验证
│   │   ├── service/StorageService.java     # 存储服务
│   │   └── monitor/                        # 健康监控
│   │       ├── HealthMonitor.java          # 定时健康检查
│   │       └── HealthMonitorListener.java  # Servlet 生命周期监听
│   └── webapp/
│       ├── WEB-INF/web.xml             # Servlet 配置
│       └── index.html                  # Web 管理界面
├── src/test/
│   └── java/org/example/
│       ├── unit/                            # 单元测试
│       │   ├── StorageServiceTest.java
│       │   ├── AuthenticationFilterTest.java
│       │   └── S3ServletTest.java
│       └── integration/                     # 集成测试
│           └── S3StorageIntegrationTest.java
└── storage/                                    # 文件存储根目录
```

## 关键技术决策

### 1. Jetty 12 EE10 API
- 使用 `org.eclipse.jetty.ee10` 包下的类
- `ServerConnector` 配置端口，通过 `server.addConnector()` 添加
- `WebAppContext.setWar()` 设置资源路径（不是 `setResourceBase()`）

### 2. API 路径设计
- **Web 界面**: 访问根路径 `/`
- **REST API**: 必须加 `/api/` 前缀
- **Servlet 映射**: `/api/*` 映射到 S3Servlet

### 3. 存储架构
- 文件存储在 `./storage/` 目录
- 每个桶对应一个子目录
- 支持嵌套目录结构（对象键可包含 `/`）
- 路径遍历防护（过滤 `../`）

### 4. Content-Type 检测
根据文件扩展名自动设置：
- `.txt` → `text/plain`
- `.html` → `text/html`
- `.json` → `application/json`
- `.png` → `image/png`
- 其他 → `application/octet-stream`

## 启动方式

```bash
# 编译
mvn clean package

# 启动（默认端口 8080）
mvn exec:java -Dexec.mainClass="org.example.Main"

# 指定端口
mvn exec:java -Dexec.mainClass="org.example.Main" -Dexec.args="9000"
```

## API 接口

所有 API 请求都需要 `/api/` 前缀。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/` | 列出所有桶 |
| PUT | `/api/{bucket}` | 创建桶 |
| DELETE | `/api/{bucket}` | 删除空桶 |
| GET | `/api/{bucket}/` | 列出桶内文件 |
| PUT | `/api/{bucket}/{key}` | 上传文件 |
| GET | `/api/{bucket}/{key}` | 下载文件 |
| DELETE | `/api/{bucket}/{key}` | 删除文件 |
| GET | `/api/health` | 健康检查 |

**查询参数**: `prefix` - 过滤以指定前缀开头的文件

## 核心类说明

### Main.java
Jetty 服务器启动类，配置：
- `QueuedThreadPool` 线程池（最大 200 线程）
- `ServerConnector` HTTP 连接器
- `WebAppContext` Web 应用上下文
- 优雅关闭钩子

### S3Servlet.java
处理 S3 风格的 HTTP 请求：
- 根据 `pathInfo` 解析桶名和对象键
- 支持的前缀过滤：`/api/{bucket}/?prefix=xxx`
- 响应格式：操作成功返回 JSON，错误返回 XML（S3 风格）

### StorageService.java
文件存储服务，核心方法：
- `createBucket()` - 创建桶目录
- `bucketExists()` - 检查桶是否存在
- `putObject()` - 上传文件（支持嵌套路径）
- `getObject()` - 获取文件
- `listObjects()` - 列出文件（支持前缀过滤）
- `deleteObject()` - 删除文件

### AuthenticationFilter.java
身份验证过滤器：
- `auth.enabled` - 是否启用验证（默认 false）
- `auth.header` - 请求头名称（默认 Authorization）
- `auth.token` - 验证令牌
- OPTIONS 请求跳过验证（CORS 预检）

### HealthMonitor.java / HealthMonitorListener.java
健康监控服务，随 Web 应用启动自动运行：
- 使用 `ScheduledExecutorService` 定期调用 `/api/health` 检测服务状态
- 默认每 10 秒检查一次，初始延迟 5 秒
- 使用 Java 11+ `HttpClient`，连接超时 5 秒
- 守护线程，应用关闭时优雅停止
- 支持通过 `server.port` 系统属性动态构建检测 URL

## 配置参数

### web.xml 上下文参数

| 参数名 | 默认值 | 说明 |
|--------|---------|------|
| `storage.root.dir` | `./storage` | 文件存储根目录 |
| `storage.max.file.size` | `104857600` | 最大文件大小（字节） |

### AuthenticationFilter 参数

| 参数名 | 默认值 | 说明 |
|--------|---------|------|
| `auth.enabled` | `false` | 是否启用身份验证 |
| `auth.header` | `Authorization` | 请求头名称 |
| `auth.token` | `Bearer your-secret-token` | 验证令牌 |

### HealthMonitor 参数

| 参数名 | 默认值 | 说明 |
|--------|---------|------|
| `health.monitor.enabled` | `true` | 是否启用健康监控 |
| `health.monitor.interval.seconds` | `10` | 检查间隔（秒） |
| `health.monitor.base.url` | `http://localhost:8080` | 检测基础 URL |

## 错误码映射

| S3 错误码 | HTTP 状态 | 说明 |
|-----------|-----------|------|
| `AccessDenied` | 401 | 身份验证失败 |
| `NoSuchBucket` | 404 | 桶不存在 |
| `NoSuchKey` | 404 | 文件不存在 |
| `BucketAlreadyExists` | 409 | 桶已存在 |
| `BucketNotEmpty` | 409 | 桶非空，无法删除 |
| `EntityTooLarge` | 413 | 文件大小超限 |
| `InvalidBucketName` | 400 | 无效的桶名 |

## Web 界面

**访问地址**: http://localhost:8080/

**技术**: 纯 HTML + CSS + JavaScript（Fetch API）

**功能**:
- 创建/删除桶
- 上传文件（支持自定义文件名）
- 列出/下载/删除文件
- 桶选择下拉框自动更新

**注意**: Web 界面使用 `API_BASE = window.location.origin + "/api"`

## 安全特性

- 路径遍历防护：过滤 `../` 并替换为 ``
- 输入验证：桶名和对象键经过清洗
- 大小限制：可配置最大上传大小
- 可选身份验证：Bearer Token 验证

## 测试

### 测试结构

| 测试类 | 数量 | 类型 | 说明 |
|--------|------|------|------|
| StorageServiceTest | 25 | 单元测试 | 存储服务逻辑 |
| AuthenticationFilterTest | 6 | 单元测试 | 认证过滤器 |
| S3ServletTest | 21 | 单元测试 | Servlet 请求处理 |
| S3StorageIntegrationTest | 23 | 集成测试 | 完整 Jetty 服务端到端测试 |

### 运行测试

```bash
mvn test
```

### 集成测试特点

- 启动完整 Jetty 服务器（随机端口）
- 使用 `@TempDir` 隔离存储目录
- 通过 `override-web.xml` 覆盖配置
- Apache HttpClient 5 发送 HTTP 请求
- 测试间有顺序依赖（`@Order` 注解）

### 测试依赖

```xml
<!-- JUnit 5 -->
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.11.3</version>
    <scope>test</scope>
</dependency>

<!-- Mockito -->
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>5.14.2</version>
    <scope>test</scope>
</dependency>

<!-- AssertJ -->
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>3.26.3</version>
    <scope>test</scope>
</dependency>

<!-- Apache HttpClient for integration tests -->
<dependency>
    <groupId>org.apache.httpcomponents.client5</groupId>
    <artifactId>httpclient5</artifactId>
    <version>5.4.1</version>
    <scope>test</scope>
</dependency>
```

## Maven 依赖关键点

```xml
<properties>
    <jetty.version>12.0.15</jetty.version>
</properties>

<!-- 重要：使用 ee10 包 -->
<dependency>
    <groupId>org.eclipse.jetty.ee10</groupId>
    <artifactId>jetty-ee10-servlet</artifactId>
</dependency>
<dependency>
    <groupId>org.eclipse.jetty.ee10</groupId>
    <artifactId>jetty-ee10-webapp</artifactId>
</dependency>

<!-- 注意：不要设置 provided scope -->
<dependency>
    <groupId>jakarta.servlet</groupId>
    <artifactId>jakarta.servlet-api</artifactId>
    <version>6.1.0</version>
</dependency>
```

## 常见问题

### Q: API 返回 404
A: 检查路径是否包含 `/api/` 前缀，例如 `http://localhost:8080/api/my-bucket`

### Q: Web 界面无法连接
A: 检查 index.html 中的 `API_BASE` 是否为 `window.location.origin + "/api"`

### Q: 启动时类找不到
A: 确认 `jakarta.servlet-api` 依赖没有 `provided` scope

### Q: 如何修改存储目录
A: 在 `web.xml` 中设置 `storage.root.dir` 参数

### Q: 文件上传后 Content-Type 不对
A: 检查 `S3Servlet.handleGetObject()` 中元数据获取顺序是否正确
