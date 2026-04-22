# Origin Proxy (回源代理) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add origin proxy functionality so that when a bucket has an origin config, requests for non-existent objects are forwarded to a configured upstream S3 endpoint.

**Architecture:** New `OriginConfig` POJO and `OriginProxyService` handle config CRUD and proxy execution. `S3Servlet` delegates to `OriginProxyService` on local object miss. New `OriginConfigServlet` provides admin API under `/admin/origin-config/`. Web UI gets an origin config modal per bucket.

**Tech Stack:** Java 21, Java `HttpClient` (already in project), Jakarta Servlet 6.1, manual JSON parsing (no Gson/Jackson — project uses manual parsing pattern in `AuthAdminServlet`), JUnit 5 + Mockito + AssertJ.

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `src/main/java/org/example/service/OriginConfig.java` | POJO: origin config fields, prefix matching |
| Create | `src/main/java/org/example/service/OriginProxyService.java` | Config CRUD + HTTP proxy execution |
| Create | `src/main/java/org/example/servlet/OriginConfigServlet.java` | Admin API: GET/PUT/DELETE `/admin/origin-config/{bucket}` |
| Modify | `src/main/java/org/example/servlet/S3Servlet.java` | Inject `OriginProxyService`, add proxy calls on local miss, add `doHead()` |
| Modify | `src/main/webapp/WEB-INF/web.xml` | Register `OriginConfigServlet` |
| Modify | `src/main/webapp/index.html` | Add origin config modal + UI wiring |
| Create | `src/test/java/org/example/unit/OriginConfigTest.java` | Unit tests for OriginConfig |
| Create | `src/test/java/org/example/unit/OriginProxyServiceTest.java` | Unit tests for OriginProxyService |
| Create | `src/test/java/org/example/integration/OriginProxyIntegrationTest.java` | Integration test with mock upstream |

---

## Task 1: OriginConfig POJO

**Files:**
- Create: `src/main/java/org/example/service/OriginConfig.java`
- Test: `src/test/java/org/example/unit/OriginConfigTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.example.unit;

import org.example.service.OriginConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OriginConfigTest {

    @Test
    void matches_shouldReturnTrueWhenKeyStartsWithPrefix() {
        var config = new OriginConfig("https://example.com", "src-bucket", "media/", "no-cache", null);
        assertThat(config.matches("media/video.mp4")).isTrue();
        assertThat(config.matches("media/sub/file.txt")).isTrue();
    }

    @Test
    void matches_shouldReturnFalseWhenKeyDoesNotStartWithPrefix() {
        var config = new OriginConfig("https://example.com", "src-bucket", "media/", "no-cache", null);
        assertThat(config.matches("docs/readme.txt")).isFalse();
        assertThat(config.matches("mediafile.txt")).isFalse();
    }

    @Test
    void matches_shouldReturnTrueForAllKeysWhenPrefixIsNull() {
        var config = new OriginConfig("https://example.com", "src-bucket", null, "no-cache", null);
        assertThat(config.matches("anything")).isTrue();
        assertThat(config.matches("deep/nested/key")).isTrue();
    }

    @Test
    void matches_shouldReturnTrueForAllKeysWhenPrefixIsEmpty() {
        var config = new OriginConfig("https://example.com", "src-bucket", "", "no-cache", null);
        assertThat(config.matches("anything")).isTrue();
    }

    @Test
    void hasCredentials_shouldReturnFalseWhenNull() {
        var config = new OriginConfig("https://example.com", "src-bucket", null, "no-cache", null);
        assertThat(config.hasCredentials()).isFalse();
    }

    @Test
    void hasCredentials_shouldReturnTrueWhenPresent() {
        var creds = new OriginConfig.Credentials("AKID", "secret");
        var config = new OriginConfig("https://example.com", "src-bucket", null, "no-cache", creds);
        assertThat(config.hasCredentials()).isTrue();
    }

    @Test
    void getters_shouldReturnAllFields() {
        var creds = new OriginConfig.Credentials("AKID", "secret");
        var config = new OriginConfig("https://example.com", "src-bucket", "img/", "no-cache", creds);
        assertThat(config.getOriginUrl()).isEqualTo("https://example.com");
        assertThat(config.getOriginBucket()).isEqualTo("src-bucket");
        assertThat(config.getPrefix()).isEqualTo("img/");
        assertThat(config.getCachePolicy()).isEqualTo("no-cache");
        assertThat(config.getCredentials()).isEqualTo(creds);
    }

    @Test
    void fromJson_shouldParseValidJson() {
        String json = """
            {"originUrl":"https://s3.amazonaws.com","originBucket":"src","prefix":"media/","cachePolicy":"no-cache","credentials":{"accessKey":"AK","secretKey":"SK"}}
            """;
        var config = OriginConfig.fromJson(json);
        assertThat(config.getOriginUrl()).isEqualTo("https://s3.amazonaws.com");
        assertThat(config.getOriginBucket()).isEqualTo("src");
        assertThat(config.getPrefix()).isEqualTo("media/");
        assertThat(config.getCachePolicy()).isEqualTo("no-cache");
        assertThat(config.hasCredentials()).isTrue();
        assertThat(config.getCredentials().accessKey()).isEqualTo("AK");
        assertThat(config.getCredentials().secretKey()).isEqualTo("SK");
    }

    @Test
    void fromJson_shouldHandleMissingOptionalFields() {
        String json = """
            {"originUrl":"https://example.com","originBucket":"src","cachePolicy":"no-cache"}
            """;
        var config = OriginConfig.fromJson(json);
        assertThat(config.getPrefix()).isNull();
        assertThat(config.hasCredentials()).isFalse();
    }

    @Test
    void toJson_shouldSerializeAllFields() {
        var creds = new OriginConfig.Credentials("AK", "SK");
        var config = new OriginConfig("https://example.com", "src", "pfx/", "no-cache", creds);
        String json = config.toJson();
        assertThat(json).contains("\"originUrl\":\"https://example.com\"");
        assertThat(json).contains("\"originBucket\":\"src\"");
        assertThat(json).contains("\"prefix\":\"pfx/\"");
        assertThat(json).contains("\"cachePolicy\":\"no-cache\"");
        assertThat(json).contains("\"accessKey\":\"AK\"");
        assertThat(json).contains("\"secretKey\":\"SK\"");
    }

    @Test
    void toJson_shouldOmitNullFields() {
        var config = new OriginConfig("https://example.com", "src", null, "no-cache", null);
        String json = config.toJson();
        assertThat(json).doesNotContain("\"prefix\"");
        assertThat(json).doesNotContain("\"credentials\"");
    }

    @Test
    void validate_shouldRejectMissingOriginUrl() {
        var config = new OriginConfig("", "src", null, "no-cache", null);
        assertThat(config.validate()).isFalse();
    }

    @Test
    void validate_shouldRejectMissingOriginBucket() {
        var config = new OriginConfig("https://example.com", "", null, "no-cache", null);
        assertThat(config.validate()).isFalse();
    }

    @Test
    void validate_shouldRejectInvalidCachePolicy() {
        var config = new OriginConfig("https://example.com", "src", null, "invalid", null);
        assertThat(config.validate()).isFalse();
    }

    @Test
    void validate_shouldAcceptValidConfig() {
        var config = new OriginConfig("https://example.com", "src", null, "no-cache", null);
        assertThat(config.validate()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest="org.example.unit.OriginConfigTest" -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: FAIL — `OriginConfig` class does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package org.example.service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OriginConfig {

    private static final Set<String> VALID_CACHE_POLICIES = Set.of("no-cache", "cache", "cache-ttl");

    private final String originUrl;
    private final String originBucket;
    private final String prefix;
    private final String cachePolicy;
    private final Credentials credentials;

    public record Credentials(String accessKey, String secretKey) {}

    public OriginConfig(String originUrl, String originBucket, String prefix, String cachePolicy, Credentials credentials) {
        this.originUrl = originUrl;
        this.originBucket = originBucket;
        this.prefix = prefix;
        this.cachePolicy = cachePolicy;
        this.credentials = credentials;
    }

    public String getOriginUrl() { return originUrl; }
    public String getOriginBucket() { return originBucket; }
    public String getPrefix() { return prefix; }
    public String getCachePolicy() { return cachePolicy; }
    public Credentials getCredentials() { return credentials; }

    public boolean matches(String objectKey) {
        if (prefix == null || prefix.isEmpty()) return true;
        return objectKey.startsWith(prefix);
    }

    public boolean hasCredentials() {
        return credentials != null
                && credentials.accessKey() != null && !credentials.accessKey().isEmpty()
                && credentials.secretKey() != null && !credentials.secretKey().isEmpty();
    }

    public boolean validate() {
        if (originUrl == null || originUrl.isBlank()) return false;
        if (originBucket == null || originBucket.isBlank()) return false;
        if (cachePolicy == null || !VALID_CACHE_POLICIES.contains(cachePolicy)) return false;
        if (credentials != null && (credentials.accessKey() == null || credentials.accessKey().isEmpty()
                || credentials.secretKey() == null || credentials.secretKey().isEmpty())) return false;
        return true;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"originUrl\":\"").append(escapeJson(originUrl)).append("\",");
        sb.append("\"originBucket\":\"").append(escapeJson(originBucket)).append("\",");
        if (prefix != null) {
            sb.append("\"prefix\":\"").append(escapeJson(prefix)).append("\",");
        }
        sb.append("\"cachePolicy\":\"").append(escapeJson(cachePolicy)).append("\"");
        if (credentials != null) {
            sb.append(",\"credentials\":{\"accessKey\":\"").append(escapeJson(credentials.accessKey()))
              .append("\",\"secretKey\":\"").append(escapeJson(credentials.secretKey())).append("\"}");
        }
        sb.append("}");
        return sb.toString();
    }

    public static OriginConfig fromJson(String json) {
        String originUrl = extractString(json, "originUrl");
        String originBucket = extractString(json, "originBucket");
        String prefix = extractString(json, "prefix");
        String cachePolicy = extractString(json, "cachePolicy");

        Credentials creds = null;
        String accessKey = extractNestedString(json, "credentials", "accessKey");
        String secretKey = extractNestedString(json, "credentials", "secretKey");
        if (accessKey != null || secretKey != null) {
            creds = new Credentials(accessKey != null ? accessKey : "", secretKey != null ? secretKey : "");
        }

        return new OriginConfig(originUrl, originBucket, prefix, cachePolicy, creds);
    }

    private static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String extractNestedString(String json, String parent, String key) {
        // Find parent object boundaries
        Pattern parentPattern = Pattern.compile("\"" + parent + "\"\\s*:\\s*\\{");
        Matcher pm = parentPattern.matcher(json);
        if (!pm.find()) return null;
        int start = pm.end();
        int depth = 1;
        int end = start;
        while (end < json.length() && depth > 0) {
            if (json.charAt(end) == '{') depth++;
            else if (json.charAt(end) == '}') depth--;
            if (depth > 0) end++;
        }
        String nested = json.substring(start, end);
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*?)\"");
        Matcher m = p.matcher(nested);
        return m.find() ? m.group(1) : null;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest="org.example.unit.OriginConfigTest" -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/service/OriginConfig.java src/test/java/org/example/unit/OriginConfigTest.java
git commit -m "feat: add OriginConfig POJO with JSON parsing and prefix matching"
```

---

## Task 2: OriginProxyService — Config CRUD

**Files:**
- Create: `src/main/java/org/example/service/OriginProxyService.java` (partial — config methods only)
- Test: `src/test/java/org/example/unit/OriginProxyServiceTest.java` (partial — config tests only)

- [ ] **Step 1: Write the failing test**

```java
package org.example.unit;

import org.example.service.OriginConfig;
import org.example.service.OriginProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OriginProxyServiceTest {

    @TempDir
    Path tempDir;

    private OriginProxyService service;

    @BeforeEach
    void setUp() {
        service = new OriginProxyService(tempDir.toString());
    }

    private void createBucket(String name) throws Exception {
        Path bucketDir = tempDir.resolve(name);
        Files.createDirectories(bucketDir);
        Files.writeString(bucketDir.resolve(".bucket-created"), String.valueOf(System.currentTimeMillis()));
    }

    @Test
    void getOriginConfig_shouldReturnNullWhenNotConfigured() throws Exception {
        createBucket("test-bucket");
        assertThat(service.getOriginConfig("test-bucket")).isNull();
    }

    @Test
    void saveOriginConfig_shouldPersistConfig() throws Exception {
        createBucket("test-bucket");
        var creds = new OriginConfig.Credentials("AK", "SK");
        var config = new OriginConfig("https://example.com", "src", "media/", "no-cache", creds);

        service.saveOriginConfig("test-bucket", config);

        var loaded = service.getOriginConfig("test-bucket");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOriginUrl()).isEqualTo("https://example.com");
        assertThat(loaded.getOriginBucket()).isEqualTo("src");
        assertThat(loaded.getPrefix()).isEqualTo("media/");
        assertThat(loaded.hasCredentials()).isTrue();
    }

    @Test
    void saveOriginConfig_shouldOverwriteExisting() throws Exception {
        createBucket("test-bucket");
        var config1 = new OriginConfig("https://old.com", "old-bucket", null, "no-cache", null);
        service.saveOriginConfig("test-bucket", config1);

        var config2 = new OriginConfig("https://new.com", "new-bucket", null, "no-cache", null);
        service.saveOriginConfig("test-bucket", config2);

        var loaded = service.getOriginConfig("test-bucket");
        assertThat(loaded.getOriginUrl()).isEqualTo("https://new.com");
    }

    @Test
    void deleteOriginConfig_shouldRemoveConfig() throws Exception {
        createBucket("test-bucket");
        var config = new OriginConfig("https://example.com", "src", null, "no-cache", null);
        service.saveOriginConfig("test-bucket", config);

        service.deleteOriginConfig("test-bucket");

        assertThat(service.getOriginConfig("test-bucket")).isNull();
    }

    @Test
    void deleteOriginConfig_shouldNotThrowWhenNotConfigured() throws Exception {
        createBucket("test-bucket");
        service.deleteOriginConfig("test-bucket"); // should not throw
    }

    @Test
    void configFilePath_shouldBeInsideBucketDir() throws Exception {
        createBucket("test-bucket");
        var config = new OriginConfig("https://example.com", "src", null, "no-cache", null);
        service.saveOriginConfig("test-bucket", config);

        assertThat(Files.exists(tempDir.resolve("test-bucket/.origin-config.json"))).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest="org.example.unit.OriginProxyServiceTest" -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: FAIL — `OriginProxyService` class does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

public class OriginProxyService {

    private static final Logger logger = LoggerFactory.getLogger(OriginProxyService.class);
    private static final String CONFIG_FILE = ".origin-config.json";

    private final String storageRootDir;
    private final HttpClient httpClient;

    public OriginProxyService(String storageRootDir) {
        this.storageRootDir = storageRootDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public OriginConfig getOriginConfig(String bucketName) {
        Path configPath = getConfigPath(bucketName);
        if (!Files.exists(configPath)) {
            return null;
        }
        try {
            String json = Files.readString(configPath);
            return OriginConfig.fromJson(json);
        } catch (Exception e) {
            logger.warn("Failed to read origin config for bucket {}", bucketName, e);
            return null;
        }
    }

    public void saveOriginConfig(String bucketName, OriginConfig config) {
        Path configPath = getConfigPath(bucketName);
        try {
            Files.writeString(configPath, config.toJson());
            logger.info("Saved origin config for bucket {}", bucketName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save origin config for bucket " + bucketName, e);
        }
    }

    public void deleteOriginConfig(String bucketName) {
        Path configPath = getConfigPath(bucketName);
        try {
            Files.deleteIfExists(configPath);
            logger.info("Deleted origin config for bucket {}", bucketName);
        } catch (Exception e) {
            logger.warn("Failed to delete origin config for bucket {}", bucketName, e);
        }
    }

    private Path getConfigPath(String bucketName) {
        return Paths.get(storageRootDir, bucketName, CONFIG_FILE);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest="org.example.unit.OriginProxyServiceTest" -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/service/OriginProxyService.java src/test/java/org/example/unit/OriginProxyServiceTest.java
git commit -m "feat: add OriginProxyService with config CRUD"
```

---

## Task 3: OriginProxyService — Proxy Execution

**Files:**
- Modify: `src/main/java/org/example/service/OriginProxyService.java` — add `proxyRequest()` method and `ProxyResult`
- Modify: `src/test/java/org/example/unit/OriginProxyServiceTest.java` — add proxy tests

- [ ] **Step 1: Write the failing test**

Add these tests to the existing `OriginProxyServiceTest` class:

```java
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.OutputStream;

// Add these tests to OriginProxyServiceTest:

    @Test
    void proxyRequest_shouldForwardGetObjectToOrigin() throws Exception {
        // Start a simple mock upstream server
        HttpServer upstream = HttpServer.create(new InetSocketAddress(0), 0);
        upstream.createContext("/src-bucket/test.txt", exchange -> {
            byte[] body = "hello from origin".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.getResponseHeaders().set("ETag", "\"abc123\"");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        upstream.start();
        try {
            int upstreamPort = upstream.getAddress().getPort();
            var config = new OriginConfig("http://localhost:" + upstreamPort, "src-bucket", null, "no-cache", null);
            service.saveOriginConfig("test-bucket", config);

            var result = service.proxyRequest("test-bucket", "test.txt", "GET", null);

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getBody()).isEqualTo("hello from origin".getBytes());
            assertThat(result.getHeaders().get("Content-Type")).isEqualTo("text/plain");
            assertThat(result.getHeaders()).containsKey("ETag");
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void proxyRequest_shouldReturnNullWhenNoConfig() throws Exception {
        createBucket("empty-bucket");
        var result = service.proxyRequest("empty-bucket", "any.txt", "GET", null);
        assertThat(result).isNull();
    }

    @Test
    void proxyRequest_shouldReturnNullWhenPrefixDoesNotMatch() throws Exception {
        createBucket("test-bucket");
        var config = new OriginConfig("http://localhost:99999", "src", "media/", "no-cache", null);
        service.saveOriginConfig("test-bucket", config);

        var result = service.proxyRequest("test-bucket", "docs/readme.txt", "GET", null);
        assertThat(result).isNull();
    }

    @Test
    void proxyRequest_shouldReturn502OnUpstreamError() throws Exception {
        createBucket("test-bucket");
        // Point to a port that's not listening
        var config = new OriginConfig("http://localhost:1", "src", null, "no-cache", null);
        service.saveOriginConfig("test-bucket", config);

        var result = service.proxyRequest("test-bucket", "test.txt", "GET", null);
        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(502);
        assertThat(result.getErrorCode()).isEqualTo("OriginError");
    }

    @Test
    void proxyRequest_shouldPassthroughUpstream404() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress(0), 0);
        upstream.createContext("/src-bucket/missing.txt", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        upstream.start();
        try {
            int port = upstream.getAddress().getPort();
            var config = new OriginConfig("http://localhost:" + port, "src-bucket", null, "no-cache", null);
            service.saveOriginConfig("test-bucket", config);

            var result = service.proxyRequest("test-bucket", "missing.txt", "GET", null);
            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(404);
            assertThat(result.getErrorCode()).isEqualTo("NoSuchKey");
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void proxyRequest_shouldHandleHeadRequest() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress(0), 0);
        upstream.createContext("/src-bucket/test.txt", exchange -> {
            exchange.getResponseHeaders().set("Content-Length", "100");
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        upstream.start();
        try {
            int port = upstream.getAddress().getPort();
            var config = new OriginConfig("http://localhost:" + port, "src-bucket", null, "no-cache", null);
            service.saveOriginConfig("test-bucket", config);

            var result = service.proxyRequest("test-bucket", "test.txt", "HEAD", null);
            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getBody()).isNull();
            assertThat(result.getHeaders().get("Content-Length")).isEqualTo("100");
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void proxyRequest_shouldForwardQueryString() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress(0), 0);
        upstream.createContext("/src-bucket/obj", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body = query != null ? query.getBytes() : "no-query".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        upstream.start();
        try {
            int port = upstream.getAddress().getPort();
            var config = new OriginConfig("http://localhost:" + port, "src-bucket", null, "no-cache", null);
            service.saveOriginConfig("test-bucket", config);

            var result = service.proxyRequest("test-bucket", "obj", "GET", "acl");
            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(new String(result.getBody())).isEqualTo("acl");
        } finally {
            upstream.stop(0);
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest="org.example.unit.OriginProxyServiceTest" -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: FAIL — `proxyRequest()` and `ProxyResult` do not exist.

- [ ] **Step 3: Write minimal implementation**

Add `ProxyResult` inner class and `proxyRequest()` method to `OriginProxyService`:

```java
// Add to OriginProxyService:

    public static class ProxyResult {
        private final int statusCode;
        private final Map<String, String> headers;
        private final byte[] body;
        private final String errorCode;

        public ProxyResult(int statusCode, Map<String, String> headers, byte[] body, String errorCode) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
            this.errorCode = errorCode;
        }

        public int getStatusCode() { return statusCode; }
        public Map<String, String> getHeaders() { return headers; }
        public byte[] getBody() { return body; }
        public String getErrorCode() { return errorCode; }
    }

    public ProxyResult proxyRequest(String bucketName, String objectKey, String method, String queryString) {
        OriginConfig config = getOriginConfig(bucketName);
        if (config == null) {
            return null;
        }

        if (!config.matches(objectKey)) {
            return null;
        }

        try {
            String url = buildUpstreamUrl(config, objectKey, queryString);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .method(method, HttpRequest.BodyPublishers.noBody());

            HttpRequest request = requestBuilder.build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            int status = response.statusCode();

            // Upstream 404 → pass through as NoSuchKey
            if (status == 404) {
                return new ProxyResult(404, extractHeaders(response), null, "NoSuchKey");
            }

            // Upstream error → 502
            if (status >= 400) {
                return new ProxyResult(502, Map.of(), null, "OriginError");
            }

            // Success
            Map<String, String> headers = extractHeaders(response);
            byte[] body = "HEAD".equals(method) ? null : response.body();
            return new ProxyResult(status, headers, body, null);

        } catch (Exception e) {
            logger.warn("Origin proxy failed for {}/{}: {}", bucketName, objectKey, e.getMessage());
            return new ProxyResult(502, Map.of(), null, "OriginError");
        }
    }

    private String buildUpstreamUrl(OriginConfig config, String objectKey, String queryString) {
        String base = config.getOriginUrl().replaceAll("/+$", "");
        String bucket = config.getOriginBucket();
        String path = "/" + bucket + "/" + objectKey;
        if (queryString != null && !queryString.isEmpty()) {
            path += "?" + queryString;
        }
        return base + path;
    }

    private Map<String, String> extractHeaders(HttpResponse<?> response) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        response.headers().map().forEach((key, values) -> {
            if (!values.isEmpty()) {
                headers.put(key, values.get(0));
            }
        });
        return headers;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest="org.example.unit.OriginProxyServiceTest" -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/service/OriginProxyService.java src/test/java/org/example/unit/OriginProxyServiceTest.java
git commit -m "feat: add proxy execution to OriginProxyService"
```

---

## Task 4: OriginConfigServlet — Admin API

**Files:**
- Create: `src/main/java/org/example/servlet/OriginConfigServlet.java`
- Modify: `src/main/webapp/WEB-INF/web.xml` — register the servlet

- [ ] **Step 1: Write the failing test**

```java
package org.example.unit;

import org.example.service.OriginConfig;
import org.example.service.OriginProxyService;
import org.example.servlet.OriginConfigServlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OriginConfigServletTest {

    @TempDir
    Path tempDir;

    private OriginConfigServlet servlet;
    private OriginProxyService proxyService;

    @BeforeEach
    void setUp() throws Exception {
        proxyService = new OriginProxyService(tempDir.toString());

        servlet = new OriginConfigServlet();
        ServletConfig config = mock(ServletConfig.class);
        ServletContext ctx = mock(ServletContext.class);
        when(config.getServletContext()).thenReturn(ctx);
        when(ctx.getInitParameter("storage.root.dir")).thenReturn(tempDir.toString());
        servlet.init(config);
    }

    private void createBucket(String name) throws Exception {
        Path bucketDir = tempDir.resolve(name);
        Files.createDirectories(bucketDir);
        Files.writeString(bucketDir.resolve(".bucket-created"), String.valueOf(System.currentTimeMillis()));
    }

    @Test
    void doGet_shouldReturn404WhenNotConfigured() throws Exception {
        createBucket("test-bucket");
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(req.getPathInfo()).thenReturn("/test-bucket");

        servlet.doGet(req, resp);

        verify(resp).setStatus(404);
    }

    @Test
    void doGet_shouldReturnConfigAsJson() throws Exception {
        createBucket("test-bucket");
        var config = new OriginConfig("https://example.com", "src", "media/", "no-cache", null);
        proxyService.saveOriginConfig("test-bucket", config);

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(req.getPathInfo()).thenReturn("/test-bucket");
        when(resp.getWriter()).thenReturn(new PrintWriter(baos, true));

        servlet.doGet(req, resp);

        verify(resp).setContentType("application/json");
        verify(resp).setStatus(200);
        String json = baos.toString();
        assertThat(json).contains("\"originUrl\":\"https://example.com\"");
        assertThat(json).contains("\"originBucket\":\"src\"");
    }

    @Test
    void doPut_shouldSaveConfig() throws Exception {
        createBucket("test-bucket");
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(req.getPathInfo()).thenReturn("/test-bucket");
        when(req.getReader()).thenReturn(new java.io.BufferedReader(new java.io.StringReader(
                "{\"originUrl\":\"https://example.com\",\"originBucket\":\"src\",\"cachePolicy\":\"no-cache\"}"
        )));

        servlet.doPut(req, resp);

        verify(resp).setStatus(200);
        assertThat(proxyService.getOriginConfig("test-bucket")).isNotNull();
    }

    @Test
    void doDelete_shouldRemoveConfig() throws Exception {
        createBucket("test-bucket");
        var config = new OriginConfig("https://example.com", "src", null, "no-cache", null);
        proxyService.saveOriginConfig("test-bucket", config);

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(req.getPathInfo()).thenReturn("/test-bucket");

        servlet.doDelete(req, resp);

        verify(resp).setStatus(204);
        assertThat(proxyService.getOriginConfig("test-bucket")).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest="org.example.unit.OriginConfigServletTest" -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: FAIL — `OriginConfigServlet` class does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package org.example.servlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.service.OriginConfig;
import org.example.service.OriginProxyService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.stream.Collectors;

public class OriginConfigServlet extends HttpServlet {

    private OriginProxyService originProxyService;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        String storageRootDir = config.getServletContext().getInitParameter("storage.root.dir");
        if (storageRootDir == null) storageRootDir = "./storage";
        this.originProxyService = new OriginProxyService(storageRootDir);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String bucketName = extractBucketName(req);
        if (bucketName == null) {
            resp.setStatus(400);
            return;
        }

        OriginConfig config = originProxyService.getOriginConfig(bucketName);
        if (config == null) {
            resp.setStatus(404);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"No origin config for this bucket\"}");
            return;
        }

        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getWriter().write(config.toJson());
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String bucketName = extractBucketName(req);
        if (bucketName == null) {
            resp.setStatus(400);
            return;
        }

        String body;
        try (BufferedReader reader = req.getReader()) {
            body = reader.lines().collect(Collectors.joining());
        }

        OriginConfig config = OriginConfig.fromJson(body);
        if (config == null || !config.validate()) {
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Invalid origin config. Required: originUrl, originBucket, cachePolicy\"}");
            return;
        }

        originProxyService.saveOriginConfig(bucketName, config);
        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getWriter().write(config.toJson());
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String bucketName = extractBucketName(req);
        if (bucketName == null) {
            resp.setStatus(400);
            return;
        }

        originProxyService.deleteOriginConfig(bucketName);
        resp.setStatus(204);
    }

    private String extractBucketName(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) return null;
        // Remove leading slash
        return pathInfo.substring(1);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest="org.example.unit.OriginConfigServletTest" -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: All tests PASS.

- [ ] **Step 5: Register in web.xml**

Add this block inside `<web-app>` in `src/main/webapp/WEB-INF/web.xml`, after the existing `AuthAdminServlet` block (after line 78):

```xml
    <!-- Origin Config Admin Servlet -->
    <servlet>
        <servlet-name>OriginConfigServlet</servlet-name>
        <servlet-class>org.example.servlet.OriginConfigServlet</servlet-class>
    </servlet>

    <servlet-mapping>
        <servlet-name>OriginConfigServlet</servlet-name>
        <url-pattern>/admin/origin-config/*</url-pattern>
    </servlet-mapping>
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/servlet/OriginConfigServlet.java src/test/java/org/example/unit/OriginConfigServletTest.java src/main/webapp/WEB-INF/web.xml
git commit -m "feat: add OriginConfigServlet admin API for origin config management"
```

---

## Task 5: S3Servlet — Origin Proxy Integration

**Files:**
- Modify: `src/main/java/org/example/servlet/S3Servlet.java` — inject `OriginProxyService`, add proxy on local miss, add `doHead()`

- [ ] **Step 1: Add OriginProxyService field and initialization**

In `S3Servlet.java`, add the field after `private long maxFileSize;` (line 27):

```java
    private OriginProxyService originProxyService;
```

Add import at top:

```java
import org.example.service.OriginProxyService;
import org.example.service.OriginProxyService.ProxyResult;
```

In `init()`, after `this.storageService = new StorageService(...)` (after line 64), add:

```java
        this.originProxyService = new OriginProxyService(storageRootDir);
```

- [ ] **Step 2: Add doHead() override**

After `doDelete()` (after line 42), add:

```java
    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handleHeadRequest(req, resp);
    }
```

- [ ] **Step 3: Add handleHeadRequest() method**

Add after `handlePostRequest()` (after line 181):

```java
    private void handleHeadRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        String pathInfo = req.getPathInfo();

        // Only handle object HEAD requests
        if (pathInfo == null || pathInfo.equals("/") || "/health".equals(pathInfo)
                || "/index.html".equals(pathInfo)) {
            // Fall back to GET which will discard body
            handleGetRequest(req, resp);
            return;
        }

        String[] pathParts = pathInfo.substring(1).split("/", 2);
        String bucketName = pathParts[0];
        String objectKey = pathParts.length > 1 ? pathParts[1] : null;

        if (objectKey == null || objectKey.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        handleHeadObject(req, resp, bucketName, objectKey);
    }
```

- [ ] **Step 4: Modify handleGetObject to proxy on miss**

Replace the existing `handleGetObject()` method (lines 251-288) with:

```java
    private void handleGetObject(HttpServletRequest req, HttpServletResponse resp, String bucketName, String objectKey) throws IOException {
        if (!storageService.bucketExists(bucketName)) {
            sendError(resp, "NoSuchBucket", "The specified bucket does not exist");
            return;
        }

        File file = storageService.getObject(bucketName, objectKey);
        if (file != null) {
            serveLocalObject(resp, bucketName, objectKey, file);
            return;
        }

        // Local miss — try origin proxy
        String queryString = req.getQueryString();
        if (tryOriginProxy(resp, bucketName, objectKey, "GET", queryString)) {
            return;
        }

        sendError(resp, "NoSuchKey", "The specified key does not exist");
    }
```

- [ ] **Step 5: Add helper methods**

Add these methods to `S3Servlet`:

```java
    private void serveLocalObject(HttpServletResponse resp, String bucketName, String objectKey, File file) throws IOException {
        var metadata = storageService.getObjectMetadata(bucketName, objectKey);
        String contentType = metadata != null ? metadata.getContentType() : "application/octet-stream";

        resp.setContentType(contentType);
        resp.setContentLengthLong(file.length());
        resp.setHeader("Last-Modified", formatRfc1123Date(file.lastModified()));

        String etag = storageService.getObjectEtag(bucketName, objectKey);
        if (etag != null) {
            resp.setHeader("ETag", "\"" + etag + "\"");
        }

        try (InputStream in = new FileInputStream(file);
             OutputStream out = resp.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }

        logger.info("Downloaded object: {}/{} (size: {} bytes)", bucketName, objectKey, file.length());
    }

    private void handleHeadObject(HttpServletRequest req, HttpServletResponse resp, String bucketName, String objectKey) throws IOException {
        if (!storageService.bucketExists(bucketName)) {
            sendError(resp, "NoSuchBucket", "The specified bucket does not exist");
            return;
        }

        File file = storageService.getObject(bucketName, objectKey);
        if (file != null) {
            var metadata = storageService.getObjectMetadata(bucketName, objectKey);
            String contentType = metadata != null ? metadata.getContentType() : "application/octet-stream";
            resp.setContentType(contentType);
            resp.setContentLengthLong(file.length());
            resp.setHeader("Last-Modified", formatRfc1123Date(file.lastModified()));
            String etag = storageService.getObjectEtag(bucketName, objectKey);
            if (etag != null) {
                resp.setHeader("ETag", "\"" + etag + "\"");
            }
            return;
        }

        // Local miss — try origin proxy
        String queryString = req.getQueryString();
        if (tryOriginProxy(resp, bucketName, objectKey, "HEAD", queryString)) {
            return;
        }

        sendError(resp, "NoSuchKey", "The specified key does not exist");
    }

    private boolean tryOriginProxy(HttpServletResponse resp, String bucketName, String objectKey, String method, String queryString) throws IOException {
        ProxyResult result = originProxyService.proxyRequest(bucketName, objectKey, method, queryString);
        if (result == null) {
            return false;
        }

        resp.setStatus(result.getStatusCode());

        if (result.getErrorCode() != null && result.getStatusCode() >= 400) {
            if ("NoSuchKey".equals(result.getErrorCode())) {
                sendError(resp, "NoSuchKey", "The specified key does not exist");
            } else {
                sendError(resp, result.getErrorCode(), "Origin request failed");
            }
            return true;
        }

        // Copy headers from origin response
        if (result.getHeaders() != null) {
            for (var entry : result.getHeaders().entrySet()) {
                String key = entry.getKey();
                // Skip hop-by-hop headers
                if (key.equalsIgnoreCase("transfer-encoding") || key.equalsIgnoreCase("connection")) continue;
                resp.setHeader(key, entry.getValue());
            }
        }

        // Write body for GET requests
        if (result.getBody() != null) {
            resp.setContentLengthLong(result.getBody().length);
            resp.getOutputStream().write(result.getBody());
        }

        logger.info("Proxied {} {}/{} from origin (status: {})", method, bucketName, objectKey, result.getStatusCode());
        return true;
    }
```

- [ ] **Step 6: Run all existing tests to verify no regressions**

Run: `mvn test -Dsurefire.useFile=false 2>&1 | tail -30`
Expected: All existing tests PASS (S3ServletTest, StorageServiceTest, etc.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/servlet/S3Servlet.java
git commit -m "feat: integrate origin proxy into S3Servlet on local object miss"
```

---

## Task 6: Web UI — Origin Config Modal

**Files:**
- Modify: `src/main/webapp/index.html`

- [ ] **Step 1: Add origin config button to action buttons area**

In `index.html`, find the `actionButtons` div (around line 84). Add a button after the Delete button (before the closing `</div>` of actionButtons, around line 93):

```html
                    <button id="originConfigBtn" onclick="openOriginConfigModal()" class="px-4 py-2 bg-purple-50 text-purple-600 hover:bg-purple-600 hover:text-white border border-purple-200 rounded-lg text-sm font-semibold transition-colors flex items-center gap-1.5">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9"></path></svg>
                        Origin
                    </button>
```

- [ ] **Step 2: Add origin config modal HTML**

Add this modal before the `<!-- Toast Container -->` comment (before line 207):

```html
    <!-- Modal: Origin Config -->
    <div id="originConfigModal" class="fixed inset-0 z-50 hidden flex items-center justify-center p-4">
        <div class="modal-overlay absolute inset-0" onclick="closeModal('originConfigModal')"></div>
        <div class="bg-white w-full max-w-lg rounded-2xl shadow-2xl relative z-10 overflow-hidden">
            <div class="p-5 border-b border-slate-100 flex justify-between items-center bg-slate-50">
                <div class="flex items-center gap-2">
                    <div class="bg-purple-600 text-white p-1.5 rounded-lg">
                        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9"></path></svg>
                    </div>
                    <h3 class="text-lg font-bold text-slate-800">Origin Config</h3>
                    <span id="originBucketLabel" class="text-xs text-slate-400 font-mono"></span>
                </div>
                <button onclick="closeModal('originConfigModal')" class="text-slate-400 hover:text-slate-600 transition-colors">
                    <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
                </button>
            </div>
            <div class="p-5 space-y-4">
                <div>
                    <label class="block text-xs font-bold text-slate-500 mb-1.5">Origin URL <span class="text-red-400">*</span></label>
                    <input type="text" id="originUrl" placeholder="https://source-bucket.s3.amazonaws.com" class="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent transition-all font-mono">
                </div>
                <div>
                    <label class="block text-xs font-bold text-slate-500 mb-1.5">Origin Bucket <span class="text-red-400">*</span></label>
                    <input type="text" id="originBucket" placeholder="source-bucket" class="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent transition-all font-mono">
                </div>
                <div>
                    <label class="block text-xs font-bold text-slate-500 mb-1.5">Prefix (optional)</label>
                    <input type="text" id="originPrefix" placeholder="media/ — leave empty for all objects" class="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent transition-all font-mono">
                </div>
                <div>
                    <label class="block text-xs font-bold text-slate-500 mb-1.5">Cache Policy</label>
                    <select id="originCachePolicy" class="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent transition-all font-mono">
                        <option value="no-cache">no-cache (always proxy)</option>
                        <option value="cache" disabled>cache (coming soon)</option>
                        <option value="cache-ttl" disabled>cache-ttl (coming soon)</option>
                    </select>
                </div>
                <details class="group">
                    <summary class="text-xs font-bold text-slate-500 cursor-pointer hover:text-purple-600 transition-colors">Credentials (optional)</summary>
                    <div class="mt-3 space-y-3">
                        <div>
                            <label class="block text-xs font-bold text-slate-500 mb-1.5">Access Key</label>
                            <input type="text" id="originAccessKey" placeholder="AKID..." class="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent transition-all font-mono">
                        </div>
                        <div>
                            <label class="block text-xs font-bold text-slate-500 mb-1.5">Secret Key</label>
                            <input type="password" id="originSecretKey" placeholder="Secret..." class="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent transition-all font-mono">
                        </div>
                    </div>
                </details>
                <div class="flex gap-2">
                    <button onclick="saveOriginConfig()" class="flex-1 py-2.5 bg-purple-600 hover:bg-purple-700 text-white font-semibold rounded-lg transition-colors shadow-md active:scale-95">Save Config</button>
                    <button onclick="deleteOriginConfig()" class="px-4 py-2.5 bg-red-50 text-red-600 hover:bg-red-600 hover:text-white border border-red-200 rounded-lg font-semibold transition-colors active:scale-95">Delete</button>
                </div>
                <div id="originConfigStatus" class="text-xs text-slate-500 bg-slate-100 rounded-lg p-3 font-mono hidden"></div>
            </div>
        </div>
    </div>
```

- [ ] **Step 3: Add origin config JavaScript functions**

Add these functions in the `<script>` section, after the Auth section (after `applyAuthMode` function, around line 529):

```javascript
        // ====== Origin Config ======
        async function openOriginConfigModal() {
            if (!state.selectedBucket) {
                showToast('Select a bucket first', 'error');
                return;
            }
            document.getElementById('originBucketLabel').textContent = state.selectedBucket;
            document.getElementById('originUrl').value = '';
            document.getElementById('originBucket').value = '';
            document.getElementById('originPrefix').value = '';
            document.getElementById('originCachePolicy').value = 'no-cache';
            document.getElementById('originAccessKey').value = '';
            document.getElementById('originSecretKey').value = '';
            document.getElementById('originConfigStatus').classList.add('hidden');

            // Load existing config
            try {
                var response = await fetch(API_BASE + '/admin/origin-config/' + state.selectedBucket);
                if (response.ok) {
                    var data = await response.json();
                    document.getElementById('originUrl').value = data.originUrl || '';
                    document.getElementById('originBucket').value = data.originBucket || '';
                    document.getElementById('originPrefix').value = data.prefix || '';
                    document.getElementById('originCachePolicy').value = data.cachePolicy || 'no-cache';
                    if (data.credentials) {
                        document.getElementById('originAccessKey').value = data.credentials.accessKey || '';
                        document.getElementById('originSecretKey').value = data.credentials.secretKey || '';
                    }
                    document.getElementById('originConfigStatus').textContent = 'Current config loaded';
                    document.getElementById('originConfigStatus').classList.remove('hidden');
                }
            } catch (e) { /* no config yet */ }

            openModal('originConfigModal');
        }

        async function saveOriginConfig() {
            var originUrl = document.getElementById('originUrl').value.trim();
            var originBucket = document.getElementById('originBucket').value.trim();
            var prefix = document.getElementById('originPrefix').value.trim();
            var cachePolicy = document.getElementById('originCachePolicy').value;
            var accessKey = document.getElementById('originAccessKey').value.trim();
            var secretKey = document.getElementById('originSecretKey').value.trim();

            if (!originUrl || !originBucket) {
                showToast('Origin URL and Bucket are required', 'error');
                return;
            }

            var payload = { originUrl: originUrl, originBucket: originBucket, cachePolicy: cachePolicy };
            if (prefix) payload.prefix = prefix;
            if (accessKey && secretKey) {
                payload.credentials = { accessKey: accessKey, secretKey: secretKey };
            }

            try {
                var response = await fetch(API_BASE + '/admin/origin-config/' + state.selectedBucket, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                if (response.ok) {
                    showToast('Origin config saved');
                    closeModal('originConfigModal');
                } else {
                    var data = await response.json();
                    showToast(data.error || 'Failed to save', 'error');
                }
            } catch (error) {
                showToast('Network error', 'error');
            }
        }

        async function deleteOriginConfig() {
            if (!confirm('Delete origin config for "' + state.selectedBucket + '"?')) return;
            try {
                var response = await fetch(API_BASE + '/admin/origin-config/' + state.selectedBucket, {
                    method: 'DELETE'
                });
                if (response.ok || response.status === 204) {
                    showToast('Origin config deleted');
                    closeModal('originConfigModal');
                } else {
                    showToast('Failed to delete', 'error');
                }
            } catch (error) {
                showToast('Network error', 'error');
            }
        }
```

- [ ] **Step 4: Show/hide origin button based on bucket selection**

In `updateActionButtons()` function (around line 373), add a line to toggle the origin button:

```javascript
            document.getElementById('originConfigBtn').classList.toggle('hidden', !hasBucket);
```

- [ ] **Step 5: Verify the web UI loads**

Run: `mvn exec:java &`
Then open http://localhost:8080/ in a browser, check:
- "Origin" button appears when a bucket is selected
- Clicking it opens the modal
- Form fields are present and the Save/Delete buttons work

Kill the server after verification.

- [ ] **Step 6: Commit**

```bash
git add src/main/webapp/index.html
git commit -m "feat: add origin config UI modal in web management interface"
```

---

## Task 7: Integration Test

**Files:**
- Create: `src/test/java/org/example/integration/OriginProxyIntegrationTest.java`

- [ ] **Step 1: Write the integration test**

```java
package org.example.integration;

import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OriginProxyIntegrationTest {

    private static Server server;
    private static int port;
    private static String baseUrl;
    private static Path testStorageDir;
    private static HttpServer mockOrigin;

    @TempDir
    static Path tempDir;

    private CloseableHttpClient httpClient;

    @BeforeAll
    static void startServer() throws Exception {
        testStorageDir = tempDir.resolve("storage");
        Files.createDirectories(testStorageDir);

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        String webappPath = new File("src/main/webapp").getAbsolutePath();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWar(webappPath);
        webAppContext.setExtractWAR(false);

        String overrideWebXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\">\n" +
                "    <context-param>\n" +
                "        <param-name>storage.root.dir</param-name>\n" +
                "        <param-value>" + testStorageDir.toAbsolutePath() + "</param-value>\n" +
                "    </context-param>\n" +
                "    <context-param>\n" +
                "        <param-name>storage.max.file.size</param-name>\n" +
                "        <param-value>104857600</param-value>\n" +
                "    </context-param>\n" +
                "    <context-param>\n" +
                "        <param-name>health.monitor.enabled</param-name>\n" +
                "        <param-value>false</param-value>\n" +
                "    </context-param>\n" +
                "    <filter>\n" +
                "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n" +
                "        <filter-class>org.example.filter.AwsV4AuthenticationFilter</filter-class>\n" +
                "        <init-param><param-name>auth.mode</param-name><param-value>both</param-value></init-param>\n" +
                "        <init-param><param-name>auth.region</param-name><param-value>us-east-1</param-value></init-param>\n" +
                "        <init-param><param-name>auth.service</param-name><param-value>s3</param-value></init-param>\n" +
                "        <init-param><param-name>auth.time.skew.minutes</param-name><param-value>15</param-value></init-param>\n" +
                "    </filter>\n" +
                "    <filter-mapping>\n" +
                "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n" +
                "        <url-pattern>/*</url-pattern>\n" +
                "    </filter-mapping>\n" +
                "    <servlet>\n" +
                "        <servlet-name>AuthAdminServlet</servlet-name>\n" +
                "        <servlet-class>org.example.servlet.AuthAdminServlet</servlet-class>\n" +
                "    </servlet>\n" +
                "    <servlet-mapping>\n" +
                "        <servlet-name>AuthAdminServlet</servlet-name>\n" +
                "        <url-pattern>/admin/*</url-pattern>\n" +
                "    </servlet-mapping>\n" +
                "    <servlet>\n" +
                "        <servlet-name>OriginConfigServlet</servlet-name>\n" +
                "        <servlet-class>org.example.servlet.OriginConfigServlet</servlet-class>\n" +
                "    </servlet>\n" +
                "    <servlet-mapping>\n" +
                "        <servlet-name>OriginConfigServlet</servlet-name>\n" +
                "        <url-pattern>/admin/origin-config/*</url-pattern>\n" +
                "    </servlet-mapping>\n" +
                "    <servlet>\n" +
                "        <servlet-name>S3Servlet</servlet-name>\n" +
                "        <servlet-class>org.example.servlet.S3Servlet</servlet-class>\n" +
                "        <load-on-startup>1</load-on-startup>\n" +
                "    </servlet>\n" +
                "    <servlet-mapping>\n" +
                "        <servlet-name>S3Servlet</servlet-name>\n" +
                "        <url-pattern>/*</url-pattern>\n" +
                "    </servlet-mapping>\n" +
                "</web-app>";

        Path overrideWebXmlPath = tempDir.resolve("override-web.xml");
        Files.writeString(overrideWebXmlPath, overrideWebXml);
        webAppContext.addOverrideDescriptor(overrideWebXmlPath.toUri().toString());

        server.setHandler(webAppContext);
        server.start();

        port = connector.getLocalPort();
        baseUrl = "http://localhost:" + port;

        // Start mock origin server
        mockOrigin = HttpServer.create(new InetSocketAddress(0), 0);
        mockOrigin.createContext("/origin-bucket/proxied.txt", exchange -> {
            byte[] body = "from origin".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.getResponseHeaders().set("ETag", "\"origin-etag\"");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        mockOrigin.createContext("/origin-bucket/media/video.mp4", exchange -> {
            byte[] body = "video content".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        mockOrigin.createContext("/origin-bucket/missing.txt", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        mockOrigin.createContext("/origin-bucket/obj", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String response = query != null ? "<Acl>" + query + "</Acl>" : "<Acl>default</Acl>";
            byte[] body = response.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        mockOrigin.start();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (mockOrigin != null) mockOrigin.stop(0);
        if (server != null) server.stop();
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClients.createDefault();
    }

    @AfterEach
    void tearDown() throws Exception {
        httpClient.close();
    }

    private String getOriginUrl() {
        return "http://localhost:" + mockOrigin.getAddress().getPort();
    }

    @Test
    @Order(1)
    void shouldCreateBucket() throws Exception {
        HttpPut put = new HttpPut(baseUrl + "/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
        }
    }

    @Test
    @Order(2)
    void shouldConfigureOriginProxy() throws Exception {
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"origin-bucket\",\"cachePolicy\":\"no-cache\"}",
                getOriginUrl());

        HttpPut put = new HttpPut(baseUrl + "/admin/origin-config/proxy-bucket");
        put.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).contains("\"originBucket\":\"origin-bucket\"");
        }
    }

    @Test
    @Order(3)
    void shouldProxyNonExistentObject() throws Exception {
        HttpGet get = new HttpGet(baseUrl + "/proxy-bucket/proxied.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).isEqualTo("from origin");
        }
    }

    @Test
    @Order(4)
    void shouldReturnLocalObjectWhenExists() throws Exception {
        // Upload a local file
        HttpPut put = new HttpPut(baseUrl + "/proxy-bucket/local.txt");
        put.setEntity(new StringEntity("local content", ContentType.TEXT_PLAIN));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
        }

        // GET should return local content, not proxy
        HttpGet get = new HttpGet(baseUrl + "/proxy-bucket/local.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).isEqualTo("local content");
        }
    }

    @Test
    @Order(5)
    void shouldProxyHeadObject() throws Exception {
        HttpHead head = new HttpHead(baseUrl + "/proxy-bucket/proxied.txt");
        try (CloseableHttpResponse resp = httpClient.execute(head)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(resp.getHeader("ETag")).isNotNull();
        }
    }

    @Test
    @Order(6)
    void shouldProxyWithPrefixFilter() throws Exception {
        // Configure prefix
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"origin-bucket\",\"prefix\":\"media/\",\"cachePolicy\":\"no-cache\"}",
                getOriginUrl());

        HttpPut put = new HttpPut(baseUrl + "/admin/origin-config/proxy-bucket");
        put.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
        }

        // Prefix match → should proxy
        HttpGet getMatch = new HttpGet(baseUrl + "/proxy-bucket/media/video.mp4");
        try (CloseableHttpResponse resp = httpClient.execute(getMatch)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).isEqualTo("video content");
        }

        // Prefix mismatch → should return 404 (object doesn't exist locally either)
        HttpGet getNoMatch = new HttpGet(baseUrl + "/proxy-bucket/proxied.txt");
        try (CloseableHttpResponse resp = httpClient.execute(getNoMatch)) {
            assertThat(resp.getCode()).isEqualTo(404);
        }
    }

    @Test
    @Order(7)
    void shouldProxyWithQueryString() throws Exception {
        // Reset to no prefix
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"origin-bucket\",\"cachePolicy\":\"no-cache\"}",
                getOriginUrl());
        HttpPut put = new HttpPut(baseUrl + "/admin/origin-config/proxy-bucket");
        put.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
        }

        // GetObject?acl should proxy with query string
        HttpGet get = new HttpGet(baseUrl + "/proxy-bucket/obj?acl");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).contains("acl");
        }
    }

    @Test
    @Order(8)
    void shouldReturn404WhenOriginReturns404() throws Exception {
        HttpGet get = new HttpGet(baseUrl + "/proxy-bucket/missing.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(404);
        }
    }

    @Test
    @Order(9)
    void shouldDeleteOriginConfig() throws Exception {
        org.apache.hc.client5.http.classic.methods.HttpDelete delete =
                new org.apache.hc.client5.http.classic.methods.HttpDelete(baseUrl + "/admin/origin-config/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(delete)) {
            assertThat(resp.getCode()).isEqualTo(204);
        }

        // After deletion, should return 404 for non-existent objects
        HttpGet get = new HttpGet(baseUrl + "/proxy-bucket/proxied.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(404);
        }
    }
}
```

- [ ] **Step 2: Run the integration test**

Run: `mvn test -pl . -Dtest="org.example.integration.OriginProxyIntegrationTest" -Dsurefire.useFile=false 2>&1 | tail -40`
Expected: All tests PASS.

- [ ] **Step 3: Run all tests to confirm no regressions**

Run: `mvn test -Dsurefire.useFile=false 2>&1 | tail -40`
Expected: All tests PASS across all test classes.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/org/example/integration/OriginProxyIntegrationTest.java
git commit -m "test: add integration tests for origin proxy feature"
```

---

## Self-Review

### Spec Coverage Check
- [x] Origin config model with JSON storage → Task 1 (OriginConfig), Task 2 (CRUD)
- [x] Prefix matching (optional, null=match all) → Task 1 (OriginConfig.matches)
- [x] Cache policy (no-cache only, reserved values) → Task 1 (validate)
- [x] Optional credentials → Task 1 (Credentials record)
- [x] OriginProxyService config CRUD → Task 2
- [x] Proxy execution with HttpClient → Task 3
- [x] GetObject proxy → Task 5 (handleGetObject)
- [x] HeadObject proxy → Task 5 (doHead + handleHeadObject)
- [x] GetObjectAcl/GetObjectTagging proxy → Task 5 (queryString forwarding)
- [x] Trigger: local object file does not exist → Task 5 (storageService.getObject check)
- [x] Admin API GET/PUT/DELETE → Task 4 (OriginConfigServlet)
- [x] web.xml registration → Task 4
- [x] Web UI config modal → Task 6
- [x] Error handling (502, 404 passthrough) → Task 3 (proxyRequest)
- [x] Unit tests → Task 1, 2, 3, 4
- [x] Integration tests → Task 7

### Placeholder Scan
No TBD, TODO, or placeholder patterns found.

### Type Consistency
- `OriginConfig` constructor: `(String, String, String, String, Credentials)` — consistent across all tasks
- `OriginProxyService.proxyRequest()` returns `ProxyResult` — consistent in Task 3 and Task 5
- `ProxyResult(statusCode, headers, body, errorCode)` — consistent across all usages
- `OriginConfig.Credentials` record with `accessKey()` and `secretKey()` — consistent across all tasks
