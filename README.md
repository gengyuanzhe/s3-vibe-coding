# S3-Like Storage Service

基于 Jetty 的 S3 风格文件存储服务，支持文件上传、下载、桶管理等操作。

## 功能特性

- 📦 创建和删除存储桶 (Bucket)
- 📤 上传文件到指定桶
- 📥 从桶下载文件
- 🗂️ 列出所有桶和桶内文件
- 🔐 可选的身份验证过滤器
- 🎨 Web 管理界面
- 💾 文件存储在本地目录
- 🔍 支持前缀过滤查询
- 🛡️ 路径遍历防护
- 📊 自动 Content-Type 检测

## 快速开始

### 1. 构建项目

```bash
mvn clean package
```

### 2. 启动服务器（推荐方式）

#### 方式一：使用 Jetty Maven 插件（最简单）

```bash
mvn jetty:run
```

指定端口:
```bash
mvn jetty:run -Djetty.http.port=9000
```

#### 方式二：使用 Jetty 分发包（Standalone 方式）

下载 Jetty 分发包（首次使用需要）：

```bash
# 下载 Jetty 12.0.15
curl -L -o jetty-home-12.0.15.tar.gz https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-home/12.0.15/jetty-home-12.0.15.tar.gz
tar -xzf jetty-home-12.0.15.tar.gz
```

启动服务：
```bash
./jetty-start.sh
```

指定端口：
```bash
PORT=9000 ./jetty-start.sh
```

**说明**：
- `jetty-start.sh` 会自动检测项目目录下的 `jetty-home-12.0.15`
- `mvn package` 会自动复制 WAR 到 `jetty-base/webapps/ROOT.war`
- WAR 文件部署到根路径 `/`，API 路径为 `/api/*`

#### 方式三：使用 Main 类启动

```bash
mvn exec:java -Dexec.mainClass="org.example.Main"
```

或者指定端口:

```bash
mvn exec:java -Dexec.mainClass="org.example.Main" -Dexec.args="9000"
```

服务器启动后，可以通过以下地址访问:
- Web 界面: http://localhost:8080
- 健康检查: http://localhost:8080/api/health

## API 接口

> **注意**: 所有 API 请求都需要加上 `/api/` 前缀

### 健康检查

```bash
curl http://localhost:8080/api/health
```

响应:
```json
{"status":"ok","service":"s3-storage"}
```

### 列出所有桶

```bash
curl http://localhost:8080/api/
```

响应格式 (XML):
```xml
<ListAllMyBucketsResult>
  <Buckets>
    <Bucket>
      <Name>my-bucket</Name>
      <CreationDate>2024-01-01T00:00:00.000Z</CreationDate>
    </Bucket>
  </Buckets>
</ListAllMyBucketsResult>
```

### 创建桶

```bash
curl -X PUT http://localhost:8080/api/my-bucket
```

响应:
```json
{"status":"success","message":"Bucket created successfully"}
```

错误响应 (桶已存在):
```xml
<Error>
  <Code>BucketAlreadyExists</Code>
  <Message>The requested bucket name is not available</Message>
</Error>
```

### 列出桶内文件

```bash
curl http://localhost:8080/api/my-bucket/
```

带前缀过滤:
```bash
curl "http://localhost:8080/api/my-bucket/?prefix=folder"
```

响应格式 (XML):
```xml
<ListBucketResult>
  <Name>my-bucket</Name>
  <Prefix></Prefix>
  <MaxKeys>1000</MaxKeys>
  <Contents>
    <Contents>
      <Key>file.txt</Key>
      <Size>1024</Size>
      <LastModified>2026-03-15T02:00:00.000Z</LastModified>
    </Contents>
  </Contents>
</ListBucketResult>
```

### 上传文件

```bash
curl -X PUT --data-binary @myfile.txt http://localhost:8080/api/my-bucket/myfile.txt
```

响应:
```json
{"status":"success","message":"Object uploaded successfully"}
```

上传到嵌套目录:
```bash
curl -X PUT --data-binary @myfile.txt http://localhost:8080/api/my-bucket/folder/subfolder/file.txt
```

### 下载文件

```bash
curl -O http://localhost:8080/api/my-bucket/myfile.txt
```

或直接查看内容:
```bash
curl http://localhost:8080/api/my-bucket/myfile.txt
```

### 获取文件元数据

```bash
curl -I http://localhost:8080/api/my-bucket/myfile.txt
```

响应头:
```
HTTP/1.1 200 OK
Content-Type: text/plain
Content-Length: 1024
```

### 删除文件

```bash
curl -X DELETE http://localhost:8080/api/my-bucket/myfile.txt
```

响应:
```json
{"status":"success","message":"Object deleted successfully"}
```

### 删除桶

```bash
curl -X DELETE http://localhost:8080/api/my-bucket
```

**注意**: 只能删除空桶，否则会返回错误。

## 错误码说明

| 错误码 | HTTP 状态 | 说明 |
|--------|-----------|------|
| `AccessDenied` | 401 | 身份验证失败 |
| `NoSuchBucket` | 404 | 桶不存在 |
| `NoSuchKey` | 404 | 文件不存在 |
| `BucketAlreadyExists` | 409 | 桶已存在 |
| `BucketNotEmpty` | 409 | 桶非空，无法删除 |
| `EntityTooLarge` | 413 | 文件大小超过限制 |
| `InvalidBucketName` | 400 | 无效的桶名 |

## 使用 Python 客户端

```python
import requests

BASE_URL = "http://localhost:8080/api"

# 创建桶
response = requests.put(f"{BASE_URL}/my-bucket")
print(response.json())

# 上传文件
with open("myfile.txt", "rb") as f:
    response = requests.put(f"{BASE_URL}/my-bucket/myfile.txt", data=f)
    print(response.json())

# 列出文件
response = requests.get(f"{BASE_URL}/my-bucket")
print(response.text)

# 下载文件
response = requests.get(f"{BASE_URL}/my-bucket/myfile.txt")
with open("downloaded.txt", "wb") as f:
    f.write(response.content)

# 删除文件
response = requests.delete(f"{BASE_URL}/my-bucket/myfile.txt")
print(response.json())
```

## 配置

### web.xml 配置

```xml
<context-param>
    <param-name>storage.root.dir</param-name>
    <param-value>./storage</param-value>
</context-param>

<context-param>
    <param-name>storage.max.file.size</param-name>
    <param-value>104857600</param-value>
</context-param>
```

### 身份验证配置

在 `web.xml` 中设置:

```xml
<filter>
    <filter-name>AuthenticationFilter</filter-name>
    <filter-class>org.example.filter.AuthenticationFilter</filter-class>
    <init-param>
        <param-name>auth.enabled</param-name>
        <param-value>true</param-value>
    </init-param>
    <init-param>
        <param-name>auth.header</param-name>
        <param-value>Authorization</param-value>
    </init-param>
    <init-param>
        <param-name>auth.token</param-name>
        <param-value>Bearer your-secret-token</param-value>
    </init-param>
</filter>
```

启用后，请求需要携带 Authorization 头:

```bash
curl -H "Authorization: Bearer your-secret-token" http://localhost:8080/api/
```

### 支持的 Content-Type

系统根据文件扩展名自动检测 Content-Type:

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

## 项目结构

```
jetty-demo/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── org/
│   │   │       └── example/
│   │   │           ├── Main.java                    # 服务器启动类
│   │   │           ├── servlet/
│   │   │           │   └── S3Servlet.java          # S3 风格 Servlet
│   │   │           ├── filter/
│   │   │           │   └── AuthenticationFilter.java  # 身份验证过滤器
│   │   │           └── service/
│   │   │               └── StorageService.java      # 存储服务
│   │   └── webapp/
│   │       ├── WEB-INF/
│   │       │   └── web.xml                 # Servlet 配置
│   │       └── index.html                  # Web 管理界面
│   └── test/
│       └── java/
│           └── org/
│               └── example/
│                   ├── unit/                         # 单元测试
│                   │   ├── StorageServiceTest.java
│                   │   ├── AuthenticationFilterTest.java
│                   │   └── S3ServletTest.java
│                   └── integration/                  # 集成测试
│                       └── S3StorageIntegrationTest.java
└── storage/                                        # 文件存储目录
```

## 技术栈

- Java 21
- Jetty 12.0.15 (EE10)
- Jakarta Servlet 6.1
- Apache Commons IO 2.18.0
- SLF4J + Simple Logging
- JUnit 5 + Mockito + AssertJ (测试)
- Apache HttpClient 5 (集成测试)

## 测试

### 运行所有测试

```bash
mvn test
```

### 测试分类

| 测试类 | 数量 | 类型 | 说明 |
|--------|------|------|------|
| StorageServiceTest | 25 | 单元测试 | 存储服务逻辑 |
| AuthenticationFilterTest | 6 | 单元测试 | 认证过滤器 |
| S3ServletTest | 21 | 单元测试 | Servlet 请求处理 |
| S3StorageIntegrationTest | 23 | 集成测试 | 完整 Jetty 服务端到端测试 |
| **总计** | **75** | | |

### 集成测试说明

集成测试会启动完整的 Jetty 服务器，使用随机端口，并通过 HTTP 客户端测试所有 API 端点：

- 健康检查
- 桶操作（创建/删除/列表）
- 文件操作（上传/下载/删除）
- 前缀过滤
- 错误处理
- CORS 预检
- Web UI 可访问性

## 安全特性

- 🔒 **路径遍历防护**: 防止 `../` 等路径遍历攻击
- 🛡️ **输入验证**: 桶名和对象键经过严格验证
- 📏 **大小限制**: 可配置的最大文件上传大小
- 🔐 **可选身份验证**: 支持 Bearer Token 验证

## 注意事项

- 文件存储在项目根目录的 `storage` 文件夹中
- 默认最大文件大小为 100MB
- 默认禁用身份验证
- 支持目录结构的对象键 (如 `folder/subfolder/file.txt`)
- 只能删除空桶
- Web 界面直接访问根路径，API 路径需要 `/api/` 前缀

## 常见问题

### Q: 如何修改默认端口？
A: 修改启动命令的参数:
```bash
mvn exec:java -Dexec.mainClass="org.example.Main" -Dexec.args="9000"
```

### Q: 如何启用身份验证？
A: 在 `web.xml` 中设置 `auth.enabled` 为 `true`，并配置 `auth.token`。

### Q: 支持哪些 HTTP 方法？
A: 支持 `GET`、`PUT`、`DELETE`、`OPTIONS`、`POST`、`HEAD`。

### Q: 文件存储在哪里？
A: 默认存储在项目根目录的 `./storage` 文件夹中，可在 `web.xml` 中配置。

### Q: 如何查看服务器日志？
A: 服务器日志输出到控制台，使用 SLF4J 日志框架。

## 许可证

MIT License
