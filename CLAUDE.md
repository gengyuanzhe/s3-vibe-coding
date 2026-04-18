# CLAUDE.md - Jetty S3-Like Storage Service

## 项目概述

基于 Jetty 12 的 S3 风格文件存储服务，提供 S3 兼容 REST API 和 Web 管理界面。支持 AWS Signature V4 鉴权、文件上传下载、桶管理、目录结构等功能。兼容 AWS SDK v2。

**项目路径**: `/Users/gengyuanzhe/code/AI/s3-vibe-coding`
**语言**: Java 21
**框架**: Jetty 12.0.15 (EE10)
**Servlet API**: Jakarta Servlet 6.1

## 项目结构

```
s3-vibe-coding/
├── pom.xml                                        # Maven 配置
├── CLAUDE.md                                      # 本文件（项目文档 + 开发指南）
├── s3-curl.py                                     # V4 签名命令行工具（boto3）
├── src/main/
│   ├── java/org/example/
│   │   ├── Main.java                              # Jetty 服务器启动入口
│   │   ├── servlet/S3Servlet.java                 # S3 API 处理（映射 /*）
│   │   ├── auth/                                  # AWS V4 鉴权
│   │   │   ├── AwsCredentials.java                # AK/SK 值对象
│   │   │   ├── AwsCredentialsProvider.java        # 凭证加载（properties 文件）
│   │   │   └── AwsV4Signer.java                   # V4 签名验证算法
│   │   ├── filter/
│   │   │   ├── AwsV4AuthenticationFilter.java     # V4 认证过滤器
│   │   │   └── CachedBodyHttpServletRequest.java  # Body 缓存（支持多次读取）
│   │   ├── service/StorageService.java            # 存储服务
│   │   └── monitor/                               # 健康监控
│   │       ├── HealthMonitor.java
│   │       └── HealthMonitorListener.java
│   ├── resources/
│   │   └── credentials.properties                 # 默认 AK/SK 凭证
│   └── webapp/
│       ├── WEB-INF/web.xml                        # Servlet + Filter 配置
│       └── index.html                             # Web 管理界面
├── src/test/
│   └── java/org/example/
│       ├── unit/                                  # 单元测试
│       │   ├── StorageServiceTest.java
│       │   ├── S3ServletTest.java
│       │   └── AwsV4SignerTest.java
│       └── integration/                           # 集成测试
│           ├── S3StorageIntegrationTest.java       # HTTP Client 集成测试
│           └── AwsSdkIntegrationTest.java          # AWS SDK v2 集成测试
└── storage/                                       # 文件存储根目录
```

## 关键技术决策

### 1. Jetty 12 EE10 API
- 使用 `org.eclipse.jetty.ee10` 包下的类
- `ServerConnector` 配置端口，通过 `server.addConnector()` 添加
- `WebAppContext.setWar()` 设置资源路径（不是 `setResourceBase()`）

### 2. S3 标准 API 路径（无前缀）
- **S3Servlet 映射**: `/*` 直接处理所有 S3 路径
- **Web 界面**: `GET /` 带 `Accept: text/html` 时返回 index.html
- **S3 API**: `/{bucket}/{key}` 标准 S3 路径，无需 `/api/` 前缀
- **AWS SDK 兼容**: `endpointOverride(URI.create("http://localhost:8080"))` 直接对接

### 3. AWS Signature V4 鉴权
- **Authorization Header 格式**: `AWS4-HMAC-SHA256 Credential=AKID/date/region/s3/aws4_request, SignedHeaders=..., Signature=...`
- **签名验证**: 服务端重构 CanonicalRequest → StringToSign → HMAC 签名链 → 恒定时间比较
- **Body 缓存**: `CachedBodyHttpServletRequest` 缓存请求体，支持签名验证和 Servlet 双重读取
- **时间窗口**: ±15 分钟防重放攻击
- **模式**: `aws-v4`（严格）/ `both`（V4 + 无签名，Web UI 用）/ `none`（关闭）

### 4. 存储架构
- 文件存储在 `./storage/` 目录
- 每个桶对应一个子目录
- 支持嵌套目录结构（对象键可包含 `/`）
- 路径遍历防护（过滤 `../`）
- ETag 侧文件：上传时计算 MD5 写入 `{key}.etag`
- 桶创建时间：`{bucket}/.bucket-created` 文件记录时间戳
- `listObjects` 自动过滤隐藏文件和 `.etag` 侧文件
- `deleteBucket` 忽略隐藏文件判断桶是否为空

### 5. S3 兼容响应格式
- 所有 XML 响应包含 `xmlns="http://s3.amazonaws.com/doc/2006-03-01/"`
- `CreateBucket` → 200 + 空 body + Location header
- `DeleteBucket` / `DeleteObject` → 204 No Content + 空 body
- `PutObject` → 200 + ETag header（MD5） + 空 body
- `GetObject` → Last-Modified（RFC 1123）+ ETag header，无 Content-Disposition
- `ListObjects` → `<Contents>` 直接在 `<ListBucketResult>` 下（非嵌套），包含 `<ETag>`
- `ListBuckets` → 真实 `CreationDate`（非硬编码）
- 错误响应：`BucketAlreadyExists` → 409, `BucketNotEmpty` → 409, `AccessDenied` → 403

### 6. Content-Type 检测
根据文件扩展名自动设置：

| 扩展名 | Content-Type |
|--------|-------------|
| `.txt` | `text/plain` |
| `.html` | `text/html` |
| `.json` | `application/json` |
| `.xml` | `application/xml` |
| `.pdf` | `application/pdf` |
| `.jpg`, `.jpeg` | `image/jpeg` |
| `.png` | `image/png` |
| `.gif` | `image/gif` |
| `.zip` | `application/zip` |
| 其他 | `application/octet-stream` |

## 启动方式

### 方式一：Main 类启动（开发推荐）

```bash
# 启动（默认端口 8080）
mvn exec:java

# 指定端口
mvn exec:java -Dexec.args="9000"
```

**IDEA 使用**：在 Maven 面板中展开 Plugins → exec → exec:java 双击运行，或创建 Maven Run Configuration，Command line 填 `exec:java` 即可（无需 `-Dexec.mainClass` 参数，已在 pom.xml 中配置）。

### 方式二：Jetty 分发包（Standalone 部署）

```bash
# 下载 Jetty 12.0.15（首次使用）
curl -L -o jetty-home-12.0.15.tar.gz https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-home/12.0.15/jetty-home-12.0.15.tar.gz
tar -xzf jetty-home-12.0.15.tar.gz``

# 启动服务
./jetty-start.sh

# 指定端口
PORT=9000 ./jetty-start.sh
```

服务器启动后访问：
- Web 界面: http://localhost:8080/
- 健康检查: http://localhost:8080/health

## API 接口

S3 标准路径，无前缀。兼容 AWS SDK v2（需设置 `pathStyleAccessEnabled(true)` 和 `chunkedEncodingEnabled(false)`）。

| 方法 | 路径 | 说明 | 成功响应 |
|------|------|------|----------|
| GET | `/` | 列出所有桶 | 200 XML |
| PUT | `/{bucket}` | 创建桶 | 200 空 body |
| DELETE | `/{bucket}` | 删除空桶 | 204 空 body |
| GET | `/{bucket}` | 列出桶内文件 | 200 XML |
| PUT | `/{bucket}/{key}` | 上传文件 | 200 + ETag header |
| GET | `/{bucket}/{key}` | 下载文件 | 200 + Last-Modified + ETag |
| DELETE | `/{bucket}/{key}` | 删除文件 | 204 空 body |
| GET | `/health` | 健康检查 | 200 JSON |
| GET | `/admin/auth-status` | 获取当前鉴权模式 | 200 JSON |
| POST | `/admin/auth-status` | 设置鉴权模式 | 200 JSON |

**查询参数**: `prefix` - 过滤以指定前缀开头的文件

## API 使用示例

**默认启用 V4 鉴权，裸 curl 请求会被 403 拒绝。** 需要使用带 V4 签名的客户端访问。

### 健康检查（无需鉴权）

```bash
curl http://localhost:8080/health
# {"status":"ok","service":"s3-storage"}
```

### 命令行工具（s3-curl.py）

项目自带基于 boto3 的命令行工具，用法：

```bash
# 安装依赖
pip3 install boto3

# 创建桶
python3 s3-curl.py mb my-bucket

# 列出所有桶
python3 s3-curl.py ls

# 上传文件
python3 s3-curl.py put my-bucket hello.txt hello.txt

# 下载文件
python3 s3-curl.py get my-bucket hello.txt

# 列出桶内文件
python3 s3-curl.py ls my-bucket

# 删除文件
python3 s3-curl.py rm my-bucket hello.txt

# 删除桶
python3 s3-curl.py rb my-bucket

# 指定其他端口
S3_ENDPOINT=http://localhost:9000 python3 s3-curl.py ls
```

### 前缀过滤（通过 boto3）

```python
s3.list_objects_v2(Bucket="my-bucket", Prefix="folder/")
```

## 客户端示例

### Python（boto3）

```python
import boto3
from botocore.config import Config

s3 = boto3.client(
    "s3",
    endpoint_url="http://localhost:8080",
    aws_access_key_id="AKIAIOSFODNN7EXAMPLE",
    aws_secret_access_key="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    region_name="us-east-1",
    config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
)

# 创建桶
s3.create_bucket(Bucket="my-bucket")

# 上传文件
s3.put_object(Bucket="my-bucket", Key="hello.txt", Body=b"Hello, S3!")

# 上传本地文件
s3.upload_file("/path/to/file.txt", "my-bucket", "file.txt")

# 下载文件
resp = s3.get_object(Bucket="my-bucket", Key="hello.txt")
print(resp["Body"].read().decode())

# 列出桶
s3.list_buckets()

# 列出桶内文件
s3.list_objects_v2(Bucket="my-bucket")

# 删除文件
s3.delete_object(Bucket="my-bucket", Key="hello.txt")
```

### AWS SDK for Java v2

```java
S3Client s3Client = S3Client.builder()
        .endpointOverride(URI.create("http://localhost:8080"))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE",
                        "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")))
        .serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .checksumValidationEnabled(false)
                .chunkedEncodingEnabled(false)
                .build())
        .build();

// 创建桶
s3Client.createBucket(CreateBucketRequest.builder().bucket("my-bucket").build());

// 上传文件
s3Client.putObject(PutObjectRequest.builder().bucket("my-bucket").key("hello.txt").build(),
        RequestBody.fromString("Hello, S3!"));

// 下载文件
byte[] content = s3Client.getObjectAsBytes(GetObjectRequest.builder()
        .bucket("my-bucket").key("hello.txt").build()).asByteArray();

// 删除文件
s3Client.deleteObject(DeleteObjectRequest.builder().bucket("my-bucket").key("hello.txt").build());
```

## 核心类说明

### Main.java
Jetty 服务器启动类，配置：
- `QueuedThreadPool` 线程池（最大 200 线程）
- `ServerConnector` HTTP 连接器
- `WebAppContext` Web 应用上下文
- 优雅关闭钩子

### S3Servlet.java
处理 S3 风格的 HTTP 请求（映射 `/*`）：
- 根据 `pathInfo` 解析桶名和对象键
- Web UI 静态文件：`/index.html` 或 `GET /` + `Accept: text/html` 时通过 `serveStaticFile()` 提供
- 响应格式：S3 标准 XML + xmlns，错误返回 S3 风格 XML
- ETag 基于内容 MD5，非随机 UUID

### StorageService.java
文件存储服务，核心方法：
- `createBucket()` - 创建桶目录 + 写 `.bucket-created` 时间戳
- `bucketExists()` - 检查桶是否存在
- `putObject()` - 上传文件（返回 MD5 ETag，写 `.etag` 侧文件）
- `getObject()` - 获取文件
- `listObjects()` - 列出文件（过滤隐藏文件和 `.etag` 侧文件，支持前缀过滤）
- `deleteObject()` - 删除文件 + 清理 `.etag` 侧文件
- `listBuckets()` - 返回 `List<BucketInfo>`（含真实创建时间）
- `getObjectEtag()` - 读取 ETag（先读侧文件，fallback 到实时计算）
- `BucketInfo` 内部类 - 桶名 + 创建时间 + ISO 格式化

### AwsV4Signer.java
AWS Signature V4 签名验证：
- 解析 `Authorization: AWS4-HMAC-SHA256 Credential=..., SignedHeaders=..., Signature=...`
- 校验 `X-Amz-Date` 时间窗口（±15 分钟）
- 构建 CanonicalRequest → StringToSign → HMAC 签名链推导
- `MessageDigest.isEqual()` 恒定时间比较防时序攻击
- 规范 URI 使用 `request.getRequestURI()`

### AwsCredentialsProvider.java
从 properties 文件加载多组 AK/SK：
```properties
accessKey.default=AKIAIOSFODNN7EXAMPLE
secretKey.default=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
```

### AwsV4AuthenticationFilter.java
替代原 Bearer Token 的 V4 认证过滤器：
- `auth.enabled` - 主开关（默认 false）
- `auth.mode` - `aws-v4` / `both` / `none`
- `/health` 请求始终放行
- 无签名请求在 `both` 模式下放行（Web UI 用）
- 签名失败返回 403 + S3 风格 XML（`SignatureDoesNotMatch` / `AccessDenied`）

### CachedBodyHttpServletRequest.java
`HttpServletRequestWrapper` 子类，缓存请求体字节数组：
- 签名验证需要读取 body 计算 SHA-256
- Servlet 也需要读取 body 获取上传内容
- 通过 `getCachedBody()` 获取缓存的原始字节数组

### HealthMonitor.java / HealthMonitorListener.java
健康监控服务，随 Web 应用启动自动运行：
- 使用 `ScheduledExecutorService` 定期调用 `/health` 检测服务状态
- 默认每 10 秒检查一次，初始延迟 5 秒
- 使用 Java 11+ `HttpClient`，连接超时 5 秒
- 守护线程，应用关闭时优雅停止

## 配置参数

### web.xml 上下文参数

| 参数名 | 默认值 | 说明 |
|--------|---------|------|
| `storage.root.dir` | `./storage` | 文件存储根目录 |
| `storage.max.file.size` | `104857600` | 最大文件大小（字节） |

### AwsV4AuthenticationFilter 参数

| 参数名 | 默认值 | 说明 |
|--------|---------|------|
| `auth.mode` | `aws-v4` | 认证模式：`aws-v4` / `both` / `none`，支持运行时动态切换 |
| `auth.region` | `us-east-1` | AWS 区域 |
| `auth.service` | `s3` | 服务名 |
| `auth.time.skew.minutes` | `15` | 时间偏差容忍（分钟） |
| `credentials.file.path` | - | AK/SK 凭证文件路径 |

### HealthMonitor 参数

| 参数名 | 默认值 | 说明 |
|--------|---------|------|
| `health.monitor.enabled` | `true` | 是否启用健康监控 |
| `health.monitor.interval.seconds` | `10` | 检查间隔（秒） |
| `health.monitor.base.url` | `http://localhost:8080` | 检测基础 URL |

## 错误码映射

| S3 错误码 | HTTP 状态 | 说明 |
|-----------|-----------|------|
| `AccessDenied` | 403 | 身份验证失败 / V4 签名无效 |
| `SignatureDoesNotMatch` | 403 | V4 签名不匹配 |
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

**注意**: Web 界面使用 `API_BASE = window.location.origin`（S3 标准路径，无 `/api` 前缀）

## 安全特性

- 路径遍历防护：过滤 `../` 并替换为 ``
- 输入验证：桶名和对象键经过清洗
- 大小限制：可配置最大上传大小
- AWS Signature V4 认证：HMAC-SHA256 签名验证，恒定时间比较
- 时间窗口校验：±15 分钟防重放攻击
- 凭证管理：properties 文件配置多组 AK/SK

## 测试

### 测试结构

| 测试类 | 类型 | 说明 |
|--------|------|------|
| StorageServiceTest | 单元测试 | 存储服务逻辑（含 BucketInfo、ETag） |
| S3ServletTest | 单元测试 | Servlet 请求处理（Mockito） |
| AwsV4SignerTest | 单元测试 | V4 签名算法验证 |
| S3StorageIntegrationTest | 集成测试 | Apache HttpClient 端到端测试 |
| AwsSdkIntegrationTest | 集成测试 | AWS SDK v2 完整 CRUD 测试 |

### 运行测试

```bash
mvn test
```

### 集成测试特点

- 启动完整 Jetty 服务器（随机端口）
- 使用 `@TempDir` 隔离存储目录
- 通过 `override-web.xml` 覆盖配置（含 V4 认证配置）
- `S3StorageIntegrationTest`: Apache HttpClient 5 发送 HTTP 请求
- `AwsSdkIntegrationTest`: AWS SDK v2 `S3Client` 直接调用（`pathStyleAccessEnabled(true)`, `chunkedEncodingEnabled(false)`）
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

<!-- AWS SDK for Java v2 - Integration Testing -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.29.45</version>
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

### Q: 如何修改默认端口
A: 启动时传参 `mvn exec:java -Dexec.args="9000"` 或 standalone 方式 `PORT=9000 ./jetty-start.sh`

### Q: AWS SDK 签名验证失败
A: 确保凭证文件配置正确，region/service 与客户端一致（默认 `us-east-1` / `s3`），SDK 设置 `pathStyleAccessEnabled(true)` 和 `chunkedEncodingEnabled(false)`

### Q: Web 界面无法连接
A: V4 鉴权默认开启（`aws-v4` 模式），浏览器无法发送 V4 签名。如需 Web UI，在 `web.xml` 中将 `auth.mode` 改为 `both`

### Q: 启动时类找不到
A: 确认 `jakarta.servlet-api` 依赖没有 `provided` scope

### Q: 如何修改存储目录
A: 在 `web.xml` 中设置 `storage.root.dir` 参数

### Q: 裸 curl 请求返回 403
A: 默认启用 V4 鉴权，裸请求被拒绝。使用 `python3 s3-curl.py` 或 AWS SDK 发送带签名的请求

### Q: 如何切换 V4 认证模式
A: 在 Web 管理界面的「Auth Settings」卡片中选择模式并点击「Apply Settings」，或通过 API 调用 `POST /admin/auth-status` 设置 `mode` 为 `none` / `both` / `aws-v4`

### Q: 如何添加新的 AK/SK
A: 在 `src/main/resources/credentials.properties` 中添加新的 `accessKey.<label>` 和 `secretKey.<label>` 条目

### Q: 如何查看服务器日志
A: 服务器日志输出到控制台，使用 SLF4J 日志框架

## 许可证

MIT License
