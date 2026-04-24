package org.example.integration;

import com.adobe.testing.s3mock.S3MockApplication;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for origin proxy using Adobe S3Mock as the upstream S3 server.
 *
 * Architecture:
 * - S3Mock (open-source S3 server) runs as the origin on a random port
 * - Our S3 service runs as the proxy on a random port
 * - Proxy is configured to point to S3Mock as origin
 *
 * This verifies compatibility with a real S3 protocol implementation,
 * not just our own code acting as both client and server.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OriginProxyS3MockTest {

    private static S3MockApplication s3Mock;
    private static int s3MockPort;
    private static String s3MockEndpoint;

    private static Server proxyServer;
    private static int proxyPort;`
    private static String proxyBaseUrl;
    private static Path proxyStorageDir;

    private static S3Client s3MockClient;

    @TempDir
    static Path tempDir;

    private CloseableHttpClient httpClient;

    @BeforeAll
    static void startServers() throws Exception {
        // --- Start Adobe S3Mock (origin) ---
        Path s3MockStorage = tempDir.resolve("s3mock-storage");
        Files.createDirectories(s3MockStorage);

        Map<String, Object> s3MockProps = new HashMap<>();
        s3MockProps.put("com.adobe.testing.s3mock.domain.root", s3MockStorage.toString());
        s3MockProps.put("http.port", "0");
        s3MockProps.put("server.port", "0");

        s3Mock = S3MockApplication.start(s3MockProps);
        s3MockPort = s3Mock.getHttpPort();
        s3MockEndpoint = "http://localhost:" + s3MockPort;

        // Create S3 client pointing to S3Mock
        s3MockClient = S3Client.builder()
                .endpointOverride(URI.create(s3MockEndpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .forcePathStyle(true)
                .build();

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

        Path overrideXmlPath = tempDir.resolve("proxy-override-web.xml");
        Files.writeString(overrideXmlPath, overrideXml);
        ctx.setOverrideDescriptor(overrideXmlPath.toFile().getAbsolutePath());

        proxyServer.setHandler(ctx);
        proxyServer.start();

        proxyPort = ((ServerConnector) proxyServer.getConnectors()[0]).getLocalPort();
        proxyBaseUrl = "http://localhost:" + proxyPort;

        System.out.println("========================================");
        System.out.println("S3Mock Origin Proxy Test");
        System.out.println("S3Mock (origin): " + s3MockEndpoint);
        System.out.println("Proxy (ours):    " + proxyBaseUrl);
        System.out.println("========================================");
    }

    @AfterAll
    static void stopServers() throws Exception {
        if (proxyServer != null) proxyServer.stop();
        if (s3Mock != null) s3Mock.stop();
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
        // Create bucket on S3Mock using AWS SDK
        s3MockClient.createBucket(CreateBucketRequest.builder().bucket("origin-data").build());

        // Create bucket on our proxy server using HTTP
        HttpPut put = new HttpPut(proxyBaseUrl + "/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(2)
    void shouldUploadFilesToS3MockOrigin() throws Exception {
        // Upload files using AWS SDK against S3Mock
        s3MockClient.putObject(PutObjectRequest.builder()
                        .bucket("origin-data").key("hello.txt").build(),
                RequestBody.fromString("Hello from S3Mock!"));

        s3MockClient.putObject(PutObjectRequest.builder()
                        .bucket("origin-data").key("data/report.csv").build(),
                RequestBody.fromString("id,name\n1,alice\n2,bob"));

        s3MockClient.putObject(PutObjectRequest.builder()
                        .bucket("origin-data").key("images/photo.jpg").build(),
                RequestBody.fromString("jpeg-binary-content"));
    }

    @Test
    @Order(3)
    void shouldConfigureOriginProxy() throws Exception {
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"origin-data\",\"cachePolicy\":\"no-cache\"}",
                s3MockEndpoint);

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
    void shouldProxyGetFromS3Mock() throws Exception {
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(EntityUtils.toString(resp.getEntity())).isEqualTo("Hello from S3Mock!");
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

        // Should return local, not S3Mock origin
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
                s3MockEndpoint);

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
                s3MockEndpoint);

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

    // ==================== S3Mock Independence ====================

    @Test
    @Order(11)
    void shouldVerifyS3MockStillHasOriginalData() throws Exception {
        // Verify S3Mock origin data is independent of proxy operations
        var objects = s3MockClient.listObjectsV2(b -> b.bucket("origin-data"));
        assertThat(objects.contents()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(objects.contents().stream().map(o -> o.key()))
                .contains("hello.txt", "data/report.csv", "images/photo.jpg");
    }

    @Test
    @Order(12)
    void shouldVerifyProxyHasNoLocalObjects() throws Exception {
        // All proxy responses were served from S3Mock origin, not locally stored
        HttpGet listProxy = new HttpGet(proxyBaseUrl + "/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(listProxy)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).doesNotContain("<Key>hello.txt</Key>");
            assertThat(body).doesNotContain("<Key>data/report.csv</Key>");
        }
    }
}
