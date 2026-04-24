package org.example.integration;

import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Process-isolated integration tests for origin proxy.
 * Starts two S3 services in separate JVM processes:
 * - Origin process: upstream S3 service holding source objects
 * - Proxy process: downstream S3 service that proxies to origin on local miss
 *
 * Each process has its own JVM heap, static state, and storage directory.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OriginProxyProcessIsolatedTest {

    private static Process originProcess;
    private static Process proxyProcess;
    private static String originBaseUrl;
    private static String proxyBaseUrl;

    @TempDir
    static Path tempDir;

    private CloseableHttpClient httpClient;

    @BeforeAll
    static void startProcesses() throws Exception {
        String classpath = System.getProperty("java.class.path");
        String webappPath = new File("src/main/webapp").getAbsolutePath();
        Path originStorage = tempDir.resolve("origin-storage");
        Path proxyStorage = tempDir.resolve("proxy-storage");
        Files.createDirectories(originStorage);
        Files.createDirectories(proxyStorage);

        originProcess = startServer(classpath, 0, originStorage.toString(), webappPath);
        proxyProcess = startServer(classpath, 0, proxyStorage.toString(), webappPath);

        int originPort = waitForReady(originProcess);
        int proxyPort = waitForReady(proxyProcess);

        originBaseUrl = "http://localhost:" + originPort;
        proxyBaseUrl = "http://localhost:" + proxyPort;

        System.out.println("========================================");
        System.out.println("Process-Isolated Origin Proxy Test");
        System.out.println("Origin PID:   " + originProcess.pid() + " at " + originBaseUrl);
        System.out.println("Proxy PID:    " + proxyProcess.pid() + " at " + proxyBaseUrl);
        System.out.println("========================================");
    }

    @AfterAll
    static void stopProcesses() {
        if (proxyProcess != null) proxyProcess.destroyForcibly();
        if (originProcess != null) originProcess.destroyForcibly();
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClients.createDefault();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpClient != null) httpClient.close();
    }

    // ==================== Process Management ====================

    private static Process startServer(String classpath, int port, String storageDir, String webappPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-cp", classpath,
                "org.example.integration.TestServerLauncher",
                String.valueOf(port), storageDir, webappPath
        );
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private static int waitForReady(Process process) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("READY:")) {
                return Integer.parseInt(line.substring("READY:".length()));
            }
            if (System.currentTimeMillis() > deadline) {
                process.destroyForcibly();
                throw new RuntimeException("Timeout waiting for server to start. Last output: " + line);
            }
        }
        process.destroyForcibly();
        throw new RuntimeException("Server process exited before becoming ready");
    }

    // ==================== Setup ====================

    @Test
    @Order(1)
    void shouldCreateBucketsOnBothProcesses() throws Exception {
        HttpPut originPut = new HttpPut(originBaseUrl + "/source-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(originPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        HttpPut proxyPut = new HttpPut(proxyBaseUrl + "/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(proxyPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(2)
    void shouldUploadFilesToOrigin() throws Exception {
        String[][] files = {
                {"hello.txt", "Hello from origin process!"},
                {"data/report.csv", "id,name\n1,alice\n2,bob"},
                {"images/photo.jpg", "fake-jpg-content"},
                {"docs/readme.md", "# Readme\nThis is from origin."},
        };
        for (String[] file : files) {
            HttpPut put = new HttpPut(originBaseUrl + "/source-bucket/" + file[0]);
            put.setEntity(new StringEntity(file[1], ContentType.TEXT_PLAIN));
            try (CloseableHttpResponse resp = httpClient.execute(put)) {
                assertThat(resp.getCode()).isEqualTo(200);
                assertThat(resp.getHeader("ETag")).isNotNull();
                EntityUtils.consume(resp.getEntity());
            }
        }
    }

    @Test
    @Order(3)
    void shouldConfigureOriginProxy() throws Exception {
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"source-bucket\",\"cachePolicy\":\"no-cache\"}",
                originBaseUrl);

        HttpPut put = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        put.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).contains("\"originBucket\":\"source-bucket\"");
        }
    }

    // ==================== Proxy Behavior ====================

    @Test
    @Order(4)
    void shouldProxyGetFromOrigin() throws Exception {
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).isEqualTo("Hello from origin process!");
        }
    }

    @Test
    @Order(5)
    void shouldProxyNestedKeys() throws Exception {
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/data/report.csv");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).contains("alice").contains("bob");
        }
    }

    @Test
    @Order(6)
    void shouldProxyHead() throws Exception {
        HttpHead head = new HttpHead(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(head)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(resp.getHeader("ETag")).isNotNull();
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(7)
    void shouldPreferLocalObjectOverOrigin() throws Exception {
        HttpPut put = new HttpPut(proxyBaseUrl + "/proxy-bucket/hello.txt");
        put.setEntity(new StringEntity("Local override!", ContentType.TEXT_PLAIN));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).isEqualTo("Local override!");
        }

        // Clean up so subsequent tests proxy again
        HttpDelete delete = new HttpDelete(proxyBaseUrl + "/proxy-bucket/hello.txt");
        httpClient.execute(delete).close();
    }

    @Test
    @Order(8)
    void shouldReturn404WhenMissingEverywhere() throws Exception {
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/nonexistent.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(404);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(9)
    void shouldFilterByPrefix() throws Exception {
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"source-bucket\",\"prefix\":\"images/\",\"cachePolicy\":\"no-cache\"}",
                originBaseUrl);

        HttpPut configPut = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        configPut.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(configPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // Matching prefix → proxy
        HttpGet imageGet = new HttpGet(proxyBaseUrl + "/proxy-bucket/images/photo.jpg");
        try (CloseableHttpResponse resp = httpClient.execute(imageGet)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).isEqualTo("fake-jpg-content");
        }

        // Non-matching prefix → 404
        HttpGet docGet = new HttpGet(proxyBaseUrl + "/proxy-bucket/docs/readme.md");
        try (CloseableHttpResponse resp = httpClient.execute(docGet)) {
            assertThat(resp.getCode()).isEqualTo(404);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(10)
    void shouldForwardQueryString() throws Exception {
        // Reset to no prefix
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"source-bucket\",\"cachePolicy\":\"no-cache\"}",
                originBaseUrl);

        HttpPut configPut = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        configPut.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(configPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // Upload object on origin
        HttpPut put = new HttpPut(originBaseUrl + "/source-bucket/test-obj");
        put.setEntity(new StringEntity("test content", ContentType.TEXT_PLAIN));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // ?acl and ?tagging should proxy with query string preserved
        HttpGet getAcl = new HttpGet(proxyBaseUrl + "/proxy-bucket/test-obj?acl");
        try (CloseableHttpResponse resp = httpClient.execute(getAcl)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        HttpGet getTagging = new HttpGet(proxyBaseUrl + "/proxy-bucket/test-obj?tagging");
        try (CloseableHttpResponse resp = httpClient.execute(getTagging)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(11)
    void shouldStopProxyingAfterConfigDeleted() throws Exception {
        HttpDelete delete = new HttpDelete(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(delete)) {
            assertThat(resp.getCode()).isEqualTo(204);
            EntityUtils.consume(resp.getEntity());
        }

        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/docs/readme.md");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(404);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(12)
    void shouldReconfigureAndProxyAgain() throws Exception {
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"source-bucket\",\"cachePolicy\":\"no-cache\"}",
                originBaseUrl);

        HttpPut configPut = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        configPut.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(configPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/docs/readme.md");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).contains("Readme").contains("from origin");
        }
    }

    // ==================== Process Isolation Verification ====================

    @Test
    @Order(13)
    void shouldVerifyOriginProcessIndependence() throws Exception {
        // Verify origin process has its own independent data
        HttpGet listOrigin = new HttpGet(originBaseUrl + "/source-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(listOrigin)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).contains("<Key>hello.txt</Key>");
            assertThat(body).contains("<Key>data/report.csv</Key>");
            assertThat(body).contains("<Key>images/photo.jpg</Key>");
            assertThat(body).contains("<Key>docs/readme.md</Key>");
            assertThat(body).contains("<Key>test-obj</Key>");
        }

        // Verify proxy process has no objects in its storage (only bucket metadata)
        HttpGet listProxy = new HttpGet(proxyBaseUrl + "/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(listProxy)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            // No local objects in proxy bucket — all were served via origin proxy
            assertThat(body).doesNotContain("<Key>hello.txt</Key>");
            assertThat(body).doesNotContain("<Key>data/report.csv</Key>");
        }
    }

    @Test
    @Order(14)
    void shouldVerifyIndependentPids() {
        // Confirm the two servers are running in separate OS processes
        assertThat(originProcess.pid()).isNotEqualTo(proxyProcess.pid());
        assertThat(originProcess.isAlive()).isTrue();
        assertThat(proxyProcess.isAlive()).isTrue();
    }
}
