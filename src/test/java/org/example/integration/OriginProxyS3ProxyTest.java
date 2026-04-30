package org.example.integration;

import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.gaul.s3proxy.AuthenticationType;
import org.gaul.s3proxy.S3Proxy;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.io.payloads.StringPayload;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for origin proxy using s3proxy (org.gaul:s3proxy) as the upstream S3 server.
 *
 * Architecture:
 * - s3proxy (open-source S3-compatible server) runs as the origin on a random port
 *   with jclouds transient (in-memory) BlobStore as backend
 * - Our S3 service runs as the proxy on a random port
 * - Proxy is configured to point to s3proxy as origin
 *
 * This verifies compatibility with a real third-party S3 protocol implementation,
 * complementing the S3Mock-based tests with a different S3 server implementation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OriginProxyS3ProxyTest {

    private static S3Proxy s3ProxyServer;
    private static int s3ProxyPort;
    private static String s3ProxyEndpoint;

    private static BlobStore blobStore;
    private static BlobStoreContext blobStoreContext;

    private static Server proxyServer;
    private static int proxyPort;
    private static String proxyBaseUrl;
    private static Path proxyStorageDir;

    @TempDir
    static Path tempDir;

    private CloseableHttpClient httpClient;

    @BeforeAll
    static void startServers() throws Exception {
        // --- Start s3proxy (origin) with in-memory BlobStore ---
        blobStoreContext = ContextBuilder.newBuilder("transient")
                .credentials("identity", "credential")
                .build(BlobStoreContext.class);
        blobStore = blobStoreContext.getBlobStore();

        s3ProxyServer = S3Proxy.builder()
                .blobStore(blobStore)
                .endpoint(new URI("http://127.0.0.1:0"))
                .ignoreUnknownHeaders(true)
                .build();
        s3ProxyServer.start();
        s3ProxyPort = s3ProxyServer.getPort();
        s3ProxyEndpoint = "http://127.0.0.1:" + s3ProxyPort;

        // --- Start our S3 service (proxy) ---
        proxyStorageDir = tempDir.resolve("proxy-storage");
        Files.createDirectories(proxyStorageDir);

        proxyServer = new Server();
        ServerConnector connector = new ServerConnector(proxyServer);
        connector.setPort(0);
        proxyServer.addConnector(connector);

        String webappPath = new File("src/main/webapp").getAbsolutePath();
        WebAppContext ctx = new WebAppContext();
        ctx.setContextPath("/");
        ctx.setWar(webappPath);
        ctx.setExtractWAR(false);

        String overrideXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\">\n"
                + "    <context-param>\n"
                + "        <param-name>storage.root.dir</param-name>\n"
                + "        <param-value>" + proxyStorageDir.toAbsolutePath() + "</param-value>\n"
                + "    </context-param>\n"
                + "    <context-param>\n"
                + "        <param-name>storage.max.file.size</param-name>\n"
                + "        <param-value>104857600</param-value>\n"
                + "    </context-param>\n"
                + "    <context-param>\n"
                + "        <param-name>health.monitor.enabled</param-name>\n"
                + "        <param-value>false</param-value>\n"
                + "    </context-param>\n"
                + "    <filter>\n"
                + "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n"
                + "        <filter-class>org.example.filter.AwsV4AuthenticationFilter</filter-class>\n"
                + "        <init-param><param-name>auth.mode</param-name><param-value>both</param-value></init-param>\n"
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

        Path overrideXmlPath = tempDir.resolve("s3proxy-test-override-web.xml");
        Files.writeString(overrideXmlPath, overrideXml);
        ctx.setOverrideDescriptor(overrideXmlPath.toFile().getAbsolutePath());

        proxyServer.setHandler(ctx);
        proxyServer.start();

        proxyPort = ((ServerConnector) proxyServer.getConnectors()[0]).getLocalPort();
        proxyBaseUrl = "http://localhost:" + proxyPort;

        System.out.println("========================================");
        System.out.println("s3proxy Origin Proxy Test");
        System.out.println("s3proxy (origin): " + s3ProxyEndpoint);
        System.out.println("Proxy (ours):     " + proxyBaseUrl);
        System.out.println("========================================");
    }

    @AfterAll
    static void stopServers() throws Exception {
        if (proxyServer != null) proxyServer.stop();
        if (s3ProxyServer != null) s3ProxyServer.stop();
        if (blobStoreContext != null) blobStoreContext.close();
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClients.createDefault();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpClient != null) httpClient.close();
    }

    // ==================== Setup ====================

    @Test
    @Order(1)
    void shouldCreateBuckets() throws Exception {
        // Create container (bucket) on s3proxy via jclouds
        blobStore.createContainerInLocation(null, "origin-data");

        // Create bucket on our proxy server
        HttpPut put = new HttpPut(proxyBaseUrl + "/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(2)
    void shouldUploadFilesToS3ProxyOrigin() throws Exception {
        // Upload files via jclouds BlobStore API to s3proxy
        putBlob("origin-data", "hello.txt", "Hello from s3proxy!");
        putBlob("origin-data", "data/report.csv", "id,name\n1,alice\n2,bob");
        putBlob("origin-data", "images/photo.jpg", "jpeg-binary-content");
    }

    private void putBlob(String container, String key, String content) {
        Blob blob = blobStore.blobBuilder(key)
                .payload(new StringPayload(content))
                .contentLength((long) content.length())
                .contentType("application/octet-stream")
                .build();
        blobStore.putBlob(container, blob);
    }

    @Test
    @Order(3)
    void shouldConfigureOriginProxy() throws Exception {
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"origin-data\",\"cachePolicy\":\"no-cache\"}",
                s3ProxyEndpoint);

        HttpPut put = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        put.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).contains("\"originBucket\":\"origin-data\"");
        }
    }

    // ==================== Proxy Behavior ====================

    @Test
    @Order(4)
    void shouldProxyGetFromS3Proxy() throws Exception {
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).isEqualTo("Hello from s3proxy!");
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
    void shouldPreferLocalOverOrigin() throws Exception {
        // Upload a local file with the same key
        HttpPut put = new HttpPut(proxyBaseUrl + "/proxy-bucket/hello.txt");
        put.setEntity(new StringEntity("Local content!", ContentType.TEXT_PLAIN));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // Should return local, not s3proxy origin
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).isEqualTo("Local content!");
        }

        // Clean up
        HttpDelete delete = new HttpDelete(proxyBaseUrl + "/proxy-bucket/hello.txt");
        httpClient.execute(delete).close();
    }

    @Test
    @Order(8)
    void shouldReturn404WhenNotOnOrigin() throws Exception {
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/nonexistent.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(404);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(9)
    void shouldFilterByPrefix() throws Exception {
        // Update config with prefix
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"origin-data\",\"prefix\":\"images/\",\"cachePolicy\":\"no-cache\"}",
                s3ProxyEndpoint);

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
            assertThat(EntityUtils.toString(resp.getEntity())).isEqualTo("jpeg-binary-content");
        }

        // Non-matching prefix → 404
        HttpGet dataGet = new HttpGet(proxyBaseUrl + "/proxy-bucket/data/report.csv");
        try (CloseableHttpResponse resp = httpClient.execute(dataGet)) {
            assertThat(resp.getCode()).isEqualTo(404);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(10)
    void shouldStopProxyingAfterConfigDeleted() throws Exception {
        // Reset to no prefix first
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"origin-data\",\"cachePolicy\":\"no-cache\"}",
                s3ProxyEndpoint);

        HttpPut configPut = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        configPut.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(configPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // Verify proxy works
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // Delete config
        HttpDelete delete = new HttpDelete(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(delete)) {
            assertThat(resp.getCode()).isEqualTo(204);
            EntityUtils.consume(resp.getEntity());
        }

        // Same key should now return 404
        HttpGet getAgain = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(getAgain)) {
            assertThat(resp.getCode()).isEqualTo(404);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(11)
    void shouldReconfigureAndProxyAgain() throws Exception {
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"origin-data\",\"cachePolicy\":\"no-cache\"}",
                s3ProxyEndpoint);

        HttpPut configPut = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        configPut.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(configPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/data/report.csv");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).contains("alice").contains("bob");
        }
    }

    // ==================== s3proxy Independence ====================

    @Test
    @Order(12)
    void shouldVerifyS3ProxyStillHasOriginalData() {
        // Verify s3proxy origin data is independent of proxy operations
        assertThat(blobStore.blobExists("origin-data", "hello.txt")).isTrue();
        assertThat(blobStore.blobExists("origin-data", "data/report.csv")).isTrue();
        assertThat(blobStore.blobExists("origin-data", "images/photo.jpg")).isTrue();
    }

    @Test
    @Order(13)
    void shouldVerifyProxyHasNoLocalObjects() throws Exception {
        // All proxy responses were served from s3proxy origin, not locally stored
        HttpGet listProxy = new HttpGet(proxyBaseUrl + "/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(listProxy)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).doesNotContain("<Key>hello.txt</Key>");
            assertThat(body).doesNotContain("<Key>data/report.csv</Key>");
        }
    }
}
