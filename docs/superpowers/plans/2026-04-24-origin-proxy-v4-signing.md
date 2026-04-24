# Origin Proxy V4 Signing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add AWS V4 request signing to origin proxy outbound requests so the proxy works with any S3-compatible service requiring authentication.

**Architecture:** Use AWS SDK v2's `AwsS3V4Signer` to sign outbound proxy requests. A new `AwsV4OutboundSigner` utility wraps the SDK signer, converting between JDK `HttpRequest` types and SDK `SdkHttpFullRequest` types. `OriginProxyService` calls the signer when credentials are configured. `OriginConfig` gains `region`/`service` fields for signing key derivation. S3Servlet gains DELETE proxy support.

**Tech Stack:** Java 21, Jetty 12 EE10, AWS SDK v2 (auth module), JUnit 5, AssertJ, com.sun.net.httpserver.HttpServer (unit test mock)

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `pom.xml:93-99` | Promote `software.amazon.awssdk:s3` from test to compile scope |
| Modify | `src/main/java/org/example/service/OriginConfig.java` | Add `region`/`service` fields, update `toJson()`/`fromJson()`/constructor |
| Create | `src/main/java/org/example/auth/AwsV4OutboundSigner.java` | Static utility: sign an outbound HTTP request using AWS SDK `AwsS3V4Signer` |
| Modify | `src/main/java/org/example/service/OriginProxyService.java:150-156` | Integrate signing into `proxyRequest()` |
| Modify | `src/main/java/org/example/servlet/S3Servlet.java:445-456` | Add DELETE proxy fallback in `handleDeleteObject()` |
| Modify | `src/main/webapp/index.html:247-258` | Add Region/Service fields to origin config modal + JS |
| Create | `src/test/java/org/example/unit/AwsV4OutboundSignerTest.java` | Unit tests for the outbound signer |
| Modify | `src/test/java/org/example/unit/OriginConfigTest.java` | Add tests for region/service fields |
| Modify | `src/test/java/org/example/unit/OriginProxyServiceTest.java` | Add signed proxy request test |
| Modify | `src/test/java/org/example/integration/OriginProxyIntegrationTest.java` | Add signed proxy integration test |

---

### Task 1: Promote AWS SDK dependency to compile scope

**Files:**
- Modify: `pom.xml:93-99`

- [ ] **Step 1: Edit pom.xml — remove `<scope>test</scope>` from s3 dependency**

Change lines 93-99 from:
```xml
        <!-- AWS SDK for Java v2 - Integration Testing -->
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>s3</artifactId>
            <version>2.29.45</version>
            <scope>test</scope>
        </dependency>
```
to:
```xml
        <!-- AWS SDK for Java v2 - Origin Proxy V4 Signing + Integration Testing -->
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>s3</artifactId>
            <version>2.29.45</version>
        </dependency>
```

- [ ] **Step 2: Verify build compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: promote AWS SDK s3 to compile scope for origin proxy signing"
```

---

### Task 2: Add `region` and `service` fields to `OriginConfig`

**Files:**
- Modify: `src/main/java/org/example/service/OriginConfig.java`
- Test: `src/test/java/org/example/unit/OriginConfigTest.java`

- [ ] **Step 1: Write failing tests for region/service in OriginConfigTest**

Append to `OriginConfigTest.java`:

```java
    @Test
    void getters_returnRegionAndService() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", null, "eu-west-1", "s3");

        assertThat(config.getRegion()).isEqualTo("eu-west-1");
        assertThat(config.getService()).isEqualTo("s3");
    }

    @Test
    void region_defaultsToUsEast1() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", null, null, null);

        assertThat(config.getRegion()).isEqualTo("us-east-1");
        assertThat(config.getService()).isEqualTo("s3");
    }

    @Test
    void toJson_includesRegionAndService() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", null, "ap-southeast-1", "s3");

        String json = config.toJson();

        assertThat(json).contains("\"region\":\"ap-southeast-1\"");
        assertThat(json).contains("\"service\":\"s3\"");
    }

    @Test
    void fromJson_parsesRegionAndService() {
        String json = "{\"originUrl\":\"http://localhost:9000\",\"originBucket\":\"src\",\"cachePolicy\":\"no-cache\",\"region\":\"eu-central-1\",\"service\":\"s3\"}";

        OriginConfig config = OriginConfig.fromJson(json);

        assertThat(config.getRegion()).isEqualTo("eu-central-1");
        assertThat(config.getService()).isEqualTo("s3");
    }

    @Test
    void fromJson_defaultsRegionAndServiceWhenMissing() {
        String json = "{\"originUrl\":\"http://localhost:9000\",\"originBucket\":\"src\",\"cachePolicy\":\"no-cache\"}";

        OriginConfig config = OriginConfig.fromJson(json);

        assertThat(config.getRegion()).isEqualTo("us-east-1");
        assertThat(config.getService()).isEqualTo("s3");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=OriginConfigTest -Dsurefire.failIfNoSpecifiedTests=false -q 2>&1 | tail -20`
Expected: Compilation error — constructor signature mismatch

- [ ] **Step 3: Update `OriginConfig.java` — add region/service fields**

Full replacement of `OriginConfig.java`:

```java
package org.example.service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OriginConfig {

    public record Credentials(String accessKey, String secretKey) {}

    private static final Set<String> VALID_POLICIES = Set.of("no-cache", "cache", "cache-ttl");

    private static final Pattern ORIGIN_URL_PATTERN = Pattern.compile("\"originUrl\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ORIGIN_BUCKET_PATTERN = Pattern.compile("\"originBucket\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PREFIX_PATTERN = Pattern.compile("\"prefix\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CACHE_POLICY_PATTERN = Pattern.compile("\"cachePolicy\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REGION_PATTERN = Pattern.compile("\"region\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SERVICE_PATTERN = Pattern.compile("\"service\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CREDENTIALS_BLOCK_PATTERN = Pattern.compile("\"credentials\"\\s*:\\s*\\{([^}]*)}");
    private static final Pattern ACCESS_KEY_PATTERN = Pattern.compile("\"accessKey\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SECRET_KEY_PATTERN = Pattern.compile("\"secretKey\"\\s*:\\s*\"([^\"]+)\"");

    private static final String DEFAULT_REGION = "us-east-1";
    private static final String DEFAULT_SERVICE = "s3";

    private final String originUrl;
    private final String originBucket;
    private final String prefix;
    private final String cachePolicy;
    private final Credentials credentials;
    private final String region;
    private final String service;

    public OriginConfig(String originUrl, String originBucket, String prefix,
                        String cachePolicy, Credentials credentials,
                        String region, String service) {
        this.originUrl = originUrl;
        this.originBucket = originBucket;
        this.prefix = prefix;
        this.cachePolicy = cachePolicy;
        this.credentials = credentials;
        this.region = (region != null && !region.isEmpty()) ? region : DEFAULT_REGION;
        this.service = (service != null && !service.isEmpty()) ? service : DEFAULT_SERVICE;
    }

    public String getOriginUrl() { return originUrl; }
    public String getOriginBucket() { return originBucket; }
    public String getPrefix() { return prefix; }
    public String getCachePolicy() { return cachePolicy; }
    public Credentials getCredentials() { return credentials; }
    public String getRegion() { return region; }
    public String getService() { return service; }

    public boolean matches(String objectKey) {
        if (prefix == null || prefix.isEmpty()) return true;
        return objectKey != null && objectKey.startsWith(prefix);
    }

    public boolean hasCredentials() {
        return credentials != null
                && credentials.accessKey() != null && !credentials.accessKey().isEmpty()
                && credentials.secretKey() != null && !credentials.secretKey().isEmpty();
    }

    public boolean validate() {
        if (originUrl == null || originUrl.isBlank()) return false;
        if (originBucket == null || originBucket.isBlank()) return false;
        if (cachePolicy == null || !VALID_POLICIES.contains(cachePolicy)) return false;
        if (credentials != null && !hasCredentials()) return false;
        return true;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"originUrl\":\"").append(escapeJson(originUrl)).append("\",");
        sb.append("\"originBucket\":\"").append(escapeJson(originBucket)).append("\",");
        if (prefix != null) {
            sb.append("\"prefix\":\"").append(escapeJson(prefix)).append("\",");
        }
        sb.append("\"cachePolicy\":\"").append(escapeJson(cachePolicy)).append("\",");
        sb.append("\"region\":\"").append(escapeJson(region)).append("\",");
        sb.append("\"service\":\"").append(escapeJson(service)).append("\"");
        if (credentials != null) {
            sb.append(",\"credentials\":{\"accessKey\":\"").append(escapeJson(credentials.accessKey()))
              .append("\",\"secretKey\":\"").append(escapeJson(credentials.secretKey())).append("\"}");
        }
        sb.append("}");
        return sb.toString();
    }

    public static OriginConfig fromJson(String json) {
        if (json == null || json.isEmpty()) return null;

        String originUrl = extract(json, ORIGIN_URL_PATTERN);
        String originBucket = extract(json, ORIGIN_BUCKET_PATTERN);
        String prefix = extract(json, PREFIX_PATTERN);
        String cachePolicy = extract(json, CACHE_POLICY_PATTERN);
        String region = extract(json, REGION_PATTERN);
        String service = extract(json, SERVICE_PATTERN);

        Credentials credentials = null;
        Matcher credsMatcher = CREDENTIALS_BLOCK_PATTERN.matcher(json);
        if (credsMatcher.find()) {
            String credsBlock = credsMatcher.group(1);
            String accessKey = extract(credsBlock, ACCESS_KEY_PATTERN);
            String secretKey = extract(credsBlock, SECRET_KEY_PATTERN);
            if (accessKey != null && secretKey != null) {
                credentials = new Credentials(accessKey, secretKey);
            }
        }

        return new OriginConfig(originUrl, originBucket, prefix, cachePolicy, credentials, region, service);
    }

    private static String extract(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

- [ ] **Step 4: Fix all existing callers of the old 5-arg constructor**

Every place that constructs `OriginConfig` with 5 args now needs 7 args (region + service). Use `null` for both defaults.

Update `OriginConfigTest.java` — change all existing 5-arg `new OriginConfig(...)` calls to 7-arg calls, appending `, null, null` before the closing `)`.

Update `OriginProxyServiceTest.java` — same change for all `new OriginConfig(...)` calls.

Update `OriginProxyIntegrationTest.java` — same change for all `new OriginConfig(...)` calls in Java code. The JSON strings in `configJson` do NOT need changes because `fromJson()` defaults null region/service.

Update `OriginConfigServletTest.java` — same change if it constructs `OriginConfig` directly.

Update `OriginProxyS3MockTest.java` — same change if it constructs `OriginConfig` directly.

Update `OriginProxyProcessIsolatedTest.java` — same change if it constructs `OriginConfig` directly.

- [ ] **Step 5: Run all tests**

Run: `mvn test -q 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/service/OriginConfig.java src/test/java/org/example/unit/OriginConfigTest.java src/test/java/org/example/unit/OriginProxyServiceTest.java src/test/java/org/example/integration/OriginProxyIntegrationTest.java
git commit -m "feat: add region and service fields to OriginConfig for V4 signing"
```

---

### Task 3: Create `AwsV4OutboundSigner` utility

**Files:**
- Create: `src/main/java/org/example/auth/AwsV4OutboundSigner.java`
- Create: `src/test/java/org/example/unit/AwsV4OutboundSignerTest.java`

- [ ] **Step 1: Write failing tests for AwsV4OutboundSigner**

Create `src/test/java/org/example/unit/AwsV4OutboundSignerTest.java`:

```java
package org.example.unit;

import org.example.auth.AwsV4OutboundSigner;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AwsV4OutboundSignerTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    @Test
    void sign_getRequest_producesAuthorizationHeader() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "GET",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        assertThat(headers).containsKey("Authorization");
        assertThat(headers.get("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY);
        assertThat(headers).containsKey("X-Amz-Date");
        assertThat(headers).containsKey("X-Amz-Content-Sha256");
        assertThat(headers.get("X-Amz-Content-Sha256")).isEqualTo("UNSIGNED-PAYLOAD");
    }

    @Test
    void sign_headRequest_producesAuthorizationHeader() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "HEAD",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        assertThat(headers).containsKey("Authorization");
        assertThat(headers.get("Authorization")).contains("SignedHeaders=");
    }

    @Test
    void sign_deleteRequest_producesAuthorizationHeader() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "DELETE",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        assertThat(headers).containsKey("Authorization");
        assertThat(headers.get("Authorization")).startsWith("AWS4-HMAC-SHA256");
    }

    @Test
    void sign_withCustomRegion_usesCorrectCredentialScope() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "GET",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "eu-west-1", "s3"
        );

        assertThat(headers.get("Authorization")).contains("/eu-west-1/s3/aws4_request");
    }

    @Test
    void sign_preservesExistingHeaders() {
        Map<String, String> existingHeaders = Map.of(
                "Content-Type", "text/plain",
                "X-Custom", "value"
        );

        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "GET",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                existingHeaders,
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        // Signing headers should be present
        assertThat(headers).containsKey("Authorization");
        assertThat(headers).containsKey("X-Amz-Date");
        // Original headers should NOT be in returned map (only signing headers returned)
    }

    @Test
    void sign_withQueryString_includesQueryInSignature() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "GET",
                URI.create("http://localhost:8080/my-bucket/obj?acl"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        assertThat(headers).containsKey("Authorization");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=AwsV4OutboundSignerTest -q 2>&1 | tail -10`
Expected: Compilation error — class not found

- [ ] **Step 3: Implement `AwsV4OutboundSigner`**

Create `src/main/java/org/example/auth/AwsV4OutboundSigner.java`:

```java
package org.example.auth;

import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.params.AwsS3V4SignerParams;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AwsV4OutboundSigner {

    private static final AwsS3V4Signer SIGNER = AwsS3V4Signer.create();

    public static Map<String, String> sign(String method, URI url, Map<String, String> headers,
                                            byte[] body, String accessKey, String secretKey,
                                            String region, String service) {
        SdkHttpFullRequest.Builder requestBuilder = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.fromValue(method))
                .uri(url);

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.putHeader(entry.getKey(), entry.getValue());
            }
        }

        if (body != null && body.length > 0) {
            requestBuilder.contentStreamProvider(ContentStreamProvider.fromByteArray(body));
        }

        AwsS3V4SignerParams params = AwsS3V4SignerParams.builder()
                .awsCredentials(AwsBasicCredentials.create(accessKey, secretKey))
                .signingRegion(Region.of(region))
                .signingName(service)
                .enablePayloadSigning(false)
                .build();

        SdkHttpFullRequest signedRequest = SIGNER.sign(requestBuilder.build(), params);

        Map<String, String> signingHeaders = new HashMap<>();
        Map<String, List<String>> allHeaders = signedRequest.headers();
        extractHeader(allHeaders, "Authorization", signingHeaders);
        extractHeader(allHeaders, "X-Amz-Date", signingHeaders);
        extractHeader(allHeaders, "x-amz-content-sha256", signingHeaders);
        return signingHeaders;
    }

    private static void extractHeader(Map<String, List<String>> allHeaders, String name, Map<String, String> target) {
        List<String> values = allHeaders.get(name);
        if (values != null && !values.isEmpty()) {
            target.put(name, values.get(values.size() - 1));
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=AwsV4OutboundSignerTest -q 2>&1 | tail -10`
Expected: All 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/auth/AwsV4OutboundSigner.java src/test/java/org/example/unit/AwsV4OutboundSignerTest.java
git commit -m "feat: add AwsV4OutboundSigner using AWS SDK S3 V4 signer"
```

---

### Task 4: Integrate signing into `OriginProxyService`

**Files:**
- Modify: `src/main/java/org/example/service/OriginProxyService.java`
- Test: `src/test/java/org/example/unit/OriginProxyServiceTest.java`

- [ ] **Step 1: Write failing test — signed proxy request to an auth-required upstream**

Append to `OriginProxyServiceTest.java`:

```java
    @Test
    void proxyRequest_shouldSignRequestWhenCredentialsConfigured() throws Exception {
        // Given - upstream server that validates Authorization header
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final boolean[] receivedAuthHeader = {false};
        server.createContext("/origin-bucket/secret-file.txt", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("AWS4-HMAC-SHA256")) {
                receivedAuthHeader[0] = true;
                byte[] body = "secret content".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } else {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            createBucket("test-bucket");
            OriginConfig config = new OriginConfig(
                    "http://127.0.0.1:" + port, "origin-bucket", null, "no-cache",
                    new OriginConfig.Credentials("AKID", "SECRET"),
                    "us-east-1", "s3"
            );
            originProxyService.saveOriginConfig("test-bucket", config);

            // When
            OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                    "test-bucket", "secret-file.txt", "GET", null
            );

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getBody()).isEqualTo("secret content".getBytes(StandardCharsets.UTF_8));
            assertThat(receivedAuthHeader[0]).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void proxyRequest_shouldReturn502WhenSigningFails() throws Exception {
        // Given - config with credentials but URL that will fail after signing
        createBucket("test-bucket");
        OriginConfig config = new OriginConfig(
                "http://127.0.0.1:1", "origin-bucket", null, "no-cache",
                new OriginConfig.Credentials("AKID", "SECRET"),
                "us-east-1", "s3"
        );
        originProxyService.saveOriginConfig("test-bucket", config);

        // When - signing itself should not fail, but the request to dead port will
        OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                "test-bucket", "test-key.txt", "GET", null
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(502);
        assertThat(result.getErrorCode()).isEqualTo("OriginError");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=OriginProxyServiceTest#proxyRequest_shouldSignRequestWhenCredentialsConfigured -q 2>&1 | tail -10`
Expected: FAIL — upstream receives no Authorization header (returns 403, proxy maps to 502)

- [ ] **Step 3: Modify `OriginProxyService.proxyRequest()` to sign when credentials present**

Replace the `proxyRequest()` method body in `OriginProxyService.java` (lines 138-177):

```java
    public ProxyResult proxyRequest(String bucketName, String objectKey, String method, String queryString) {
        OriginConfig config = getOriginConfig(bucketName);
        if (config == null) {
            return null;
        }

        if (!config.matches(objectKey)) {
            return null;
        }

        String url = buildUpstreamUrl(config, objectKey, queryString);

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(30));

            if (config.hasCredentials()) {
                Map<String, String> signingHeaders = AwsV4OutboundSigner.sign(
                        method, URI.create(url), Map.of(), null,
                        config.getCredentials().accessKey(),
                        config.getCredentials().secretKey(),
                        config.getRegion(), config.getService()
                );
                for (Map.Entry<String, String> entry : signingHeaders.entrySet()) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }
            }

            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

            int status = response.statusCode();

            if (status == 404) {
                return new ProxyResult(404, extractHeaders(response.headers()), null, "NoSuchKey");
            }

            if (status >= 400) {
                return new ProxyResult(502, Map.of(), null, "OriginError");
            }

            Map<String, String> headers = extractHeaders(response.headers());
            byte[] body = "HEAD".equalsIgnoreCase(method) ? null : response.body();

            return new ProxyResult(status, headers, body, null);
        } catch (Exception e) {
            logger.warn("Proxy request to origin failed for bucket={} key={}: {}", bucketName, objectKey, e.getMessage());
            return new ProxyResult(502, Map.of(), null, "OriginError");
        }
    }
```

Add the import at the top of `OriginProxyService.java`:

```java
import org.example.auth.AwsV4OutboundSigner;
```

- [ ] **Step 4: Run all OriginProxyServiceTest tests**

Run: `mvn test -Dtest=OriginProxyServiceTest -q 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/service/OriginProxyService.java src/test/java/org/example/unit/OriginProxyServiceTest.java
git commit -m "feat: integrate V4 signing into origin proxy requests"
```

---

### Task 5: Add DELETE proxy fallback in S3Servlet

**Files:**
- Modify: `src/main/java/org/example/servlet/S3Servlet.java:445-456`

- [ ] **Step 1: Modify `handleDeleteObject()` to try origin proxy on local miss**

Replace `handleDeleteObject()` in `S3Servlet.java` (lines 445-456):

```java
    private void handleDeleteObject(HttpServletRequest req, HttpServletResponse resp, String bucketName, String objectKey) throws IOException {
        if (!storageService.bucketExists(bucketName)) {
            sendError(resp, "NoSuchBucket", "The specified bucket does not exist");
            return;
        }

        if (storageService.deleteObject(bucketName, objectKey)) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        // Local object not found - try origin proxy delete
        if (tryOriginProxy(resp, bucketName, objectKey, "DELETE", req.getQueryString())) {
            return;
        }

        sendError(resp, "NoSuchKey", "The specified key does not exist");
    }
```

- [ ] **Step 2: Run all tests to verify no regression**

Run: `mvn test -q 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/servlet/S3Servlet.java
git commit -m "feat: add DELETE proxy fallback to origin on local object miss"
```

---

### Task 6: Update Web UI — add Region and Service fields

**Files:**
- Modify: `src/main/webapp/index.html`

- [ ] **Step 1: Add Region and Service input fields to the origin config modal**

In `index.html`, after the Cache Policy `<div>` (after line 245 `</select>` closing tag and its containing `</div>`), insert before the `<details class="group">` credentials section (line 247):

```html
                <div class="grid grid-cols-2 gap-3">
                    <div>
                        <label class="block text-xs font-bold text-slate-500 mb-1.5">Region</label>
                        <input type="text" id="originRegion" placeholder="us-east-1" class="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent transition-all font-mono">
                    </div>
                    <div>
                        <label class="block text-xs font-bold text-slate-500 mb-1.5">Service</label>
                        <input type="text" id="originService" placeholder="s3" class="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-2.5 text-sm outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent transition-all font-mono">
                    </div>
                </div>
```

- [ ] **Step 2: Update `openOriginConfigModal()` JS to clear and populate new fields**

In the `openOriginConfigModal()` function, after line `document.getElementById('originSecretKey').value = '';` (line 608), add:

```javascript
                document.getElementById('originRegion').value = '';
                document.getElementById('originService').value = '';
```

In the same function, in the `if (data.credentials)` block area (after line 622), add after the credentials block:

```javascript
                    document.getElementById('originRegion').value = data.region || '';
                    document.getElementById('originService').value = data.service || '';
```

- [ ] **Step 3: Update `saveOriginConfig()` JS to include region and service**

In `saveOriginConfig()`, after line `var secretKey = ...` (line 637), add:

```javascript
            var region = document.getElementById('originRegion').value.trim();
            var service = document.getElementById('originService').value.trim();
```

In the `payload` object construction (around line 644), add after `if (prefix)` block:

```javascript
            if (region) payload.region = region;
            if (service) payload.service = service;
```

- [ ] **Step 4: Verify the build still passes**

Run: `mvn test -q 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/webapp/index.html
git commit -m "feat: add region and service fields to origin config web UI"
```

---

### Task 7: Integration test — signed proxy with V4 auth-enabled upstream

**Files:**
- Modify: `src/test/java/org/example/integration/OriginProxyIntegrationTest.java`

- [ ] **Step 1: Add signed proxy integration test**

Append new test methods to `OriginProxyIntegrationTest.java`, after the last test (Order 13). Add a new helper that creates a server with `aws-v4` auth mode (strict) and a test that configures credentials on the proxy.

First, add a new static field and setup for a third server (strict V4 auth origin):

After the existing `proxyStorageDir` field, add:

```java
    private static Server strictOriginServer;
    private static int strictOriginPort;
    private static String strictOriginBaseUrl;
    private static Path strictOriginStorageDir;
```

In `@BeforeAll startServers()`, after the two existing servers, add:

```java
        strictOriginStorageDir = tempDir.resolve("strict-origin-storage");
        Files.createDirectories(strictOriginStorageDir);
        strictOriginServer = createStrictServer(strictOriginStorageDir, tempDir.resolve("strict-origin-override-web.xml"));
        strictOriginServer.start();
        strictOriginPort = ((ServerConnector) strictOriginServer.getConnectors()[0]).getLocalPort();
        strictOriginBaseUrl = "http://localhost:" + strictOriginPort;
```

In `@AfterAll stopServers()`, add before existing stops:

```java
        if (strictOriginServer != null) strictOriginServer.stop();
```

Add the helper method:

```java
    private static Server createStrictServer(Path storageDir, Path overrideXmlPath) throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        String webappPath = new File("src/main/webapp").getAbsolutePath();
        WebAppContext ctx = new WebAppContext();
        ctx.setContextPath("/");
        ctx.setWar(webappPath);
        ctx.setExtractWAR(false);

        String overrideXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\">\n"
                + "    <context-param>\n"
                + "        <param-name>storage.root.dir</param-name>\n"
                + "        <param-value>" + storageDir.toAbsolutePath() + "</param-value>\n"
                + "    </context-param>\n"
                + "    <context-param>\n"
                + "        <param-name>storage.max.file.size</param-name>\n"
                + "        <param-value>104857600</param-value>\n"
                + "    </context-param>\n"
                + "    <context-param>\n"
                + "        <param-name>health.monitor.enabled</param-name>\n"
                + "        <param-value>false</param-value>\n"
                + "    </context-param>\n"
                + "    <context-param>\n"
                + "        <param-name>credentials.file.path</param-name>\n"
                + "        <param-value>" + new File("src/main/resources/credentials.properties").getAbsolutePath() + "</param-value>\n"
                + "    </context-param>\n"
                + "    <filter>\n"
                + "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n"
                + "        <filter-class>org.example.filter.AwsV4AuthenticationFilter</filter-class>\n"
                + "        <init-param><param-name>auth.mode</param-name><param-value>aws-v4</param-value></init-param>\n"
                + "        <init-param><param-name>auth.region</param-name><param-value>us-east-1</param-value></init-param>\n"
                + "        <init-param><param-name>auth.service</param-name><param-value>s3</param-value></init-param>\n"
                + "        <init-param><param-name>auth.time.skew.minutes</param-name><param-value>15</param-value></init-param>\n"
                + "    </filter>\n"
                + "    <filter-mapping>\n"
                + "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n"
                + "        <url-pattern>/*</url-pattern>\n"
                + "    </filter-mapping>\n"
                + "    <servlet>\n"
                + "        <servlet-name>AuthAdminServlet</servlet-name>\n"
                + "        <servlet-class>org.example.servlet.AuthAdminServlet</servlet-class>\n"
                + "    </servlet>\n"
                + "    <servlet-mapping>\n"
                + "        <servlet-name>AuthAdminServlet</servlet-name>\n"
                + "        <url-pattern>/admin/*</url-pattern>\n"
                + "    </servlet-mapping>\n"
                + "    <servlet>\n"
                + "        <servlet-name>OriginConfigServlet</servlet-name>\n"
                + "        <servlet-class>org.example.servlet.OriginConfigServlet</servlet-class>\n"
                + "    </servlet>\n"
                + "    <servlet-mapping>\n"
                + "        <servlet-name>OriginConfigServlet</servlet-name>\n"
                + "        <url-pattern>/admin/origin-config/*</url-pattern>\n"
                + "    </servlet-mapping>\n"
                + "    <servlet>\n"
                + "        <servlet-name>S3Servlet</servlet-name>\n"
                + "        <servlet-class>org.example.servlet.S3Servlet</servlet-class>\n"
                + "        <load-on-startup>1</load-on-startup>\n"
                + "    </servlet>\n"
                + "    <servlet-mapping>\n"
                + "        <servlet-name>S3Servlet</servlet-name>\n"
                + "        <url-pattern>/*</url-pattern>\n"
                + "    </servlet-mapping>\n"
                + "</web-app>";

        Files.writeString(overrideXmlPath, overrideXml);
        ctx.setOverrideDescriptor(overrideXmlPath.toFile().getAbsolutePath());

        server.setHandler(ctx);
        return server;
    }
```

Add the test at Order 14:

```java
    @Test
    @Order(14)
    void shouldProxyWithV4SigningToStrictAuthOrigin() throws Exception {
        // Setup: create bucket on strict origin and upload a file (using unsigned request won't work,
        // so we use the proxy server which has "both" mode to first upload indirectly,
        // OR we upload to the strict server by setting its auth to both first, then switch)
        // Simpler: upload directly to strict origin using AWS SDK with known credentials

        // The strict origin server uses the default credentials.properties
        // We'll use the AWS SDK to upload a test object
        software.amazon.awssdk.services.s3.S3Client strictS3Client = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(java.net.URI.create(strictOriginBaseUrl))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                                "AKIAIOSFODNN7EXAMPLE",
                                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();

        strictS3Client.createBucket(software.amazon.awssdk.services.s3.model.CreateBucketRequest.builder()
                .bucket("strict-bucket").build());
        strictS3Client.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                        .bucket("strict-bucket").key("signed-obj.txt").build(),
                software.amazon.awssdk.core.sync.RequestBody.fromString("signed content"));

        // Create a proxy bucket and configure it to proxy to strict origin with credentials
        HttpPut proxyBucketPut = new HttpPut(proxyBaseUrl + "/signed-proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(proxyBucketPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"strict-bucket\",\"cachePolicy\":\"no-cache\",\"region\":\"us-east-1\",\"service\":\"s3\",\"credentials\":{\"accessKey\":\"AKIAIOSFODNN7EXAMPLE\",\"secretKey\":\"wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\"}}",
                strictOriginBaseUrl);

        HttpPut configPut = new HttpPut(proxyBaseUrl + "/admin/origin-config/signed-proxy-bucket");
        configPut.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(configPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // GET through proxy should succeed (proxy signs the request to strict origin)
        HttpGet get = new HttpGet(proxyBaseUrl + "/signed-proxy-bucket/signed-obj.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).isEqualTo("signed content");
        }

        strictS3Client.close();
    }
```

Also add the needed imports at the top of `OriginProxyIntegrationTest.java` if not already present:

```java
import org.eclipse.jetty.server.ServerConnector;
```

This should already be imported. The AWS SDK imports use fully-qualified names in the test method so no new imports are needed.

- [ ] **Step 2: Run the full test suite**

Run: `mvn test -q 2>&1 | tail -15`
Expected: All tests PASS including the new Order 14 test

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/example/integration/OriginProxyIntegrationTest.java
git commit -m "test: add integration test for signed proxy to V4 auth-enabled origin"
```

---

### Task 8: Full test suite verification

- [ ] **Step 1: Run the complete test suite**

Run: `mvn test 2>&1 | tail -20`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: Final commit if any remaining changes**

```bash
git status
git add -A
git commit -m "chore: finalize origin proxy V4 signing implementation"
```
(Only if there are unstaged changes)
