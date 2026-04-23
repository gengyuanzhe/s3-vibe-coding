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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for origin proxy feature.
 * Starts two real S3 service instances:
 * - originServer: upstream S3 service (source of truth for objects)
 * - proxyServer: downstream S3 service (proxies to origin on local miss)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OriginProxyIntegrationTest {

    private static Server originServer;
    private static int originPort;
    private static String originBaseUrl;
    private static Path originStorageDir;

    private static Server proxyServer;
    private static int proxyPort;
    private static String proxyBaseUrl;
    private static Path proxyStorageDir;

    @TempDir
    static Path tempDir;

    private CloseableHttpClient httpClient;

    @BeforeAll
    static void startServers() throws Exception {
        originStorageDir = tempDir.resolve("origin-storage");
        Files.createDirectories(originStorageDir);
        proxyStorageDir = tempDir.resolve("proxy-storage");
        Files.createDirectories(proxyStorageDir);

        originServer = createServer(originStorageDir, tempDir.resolve("origin-override-web.xml"));
        proxyServer = createServer(proxyStorageDir, tempDir.resolve("proxy-override-web.xml"));

        originServer.start();
        proxyServer.start();

        originPort = ((ServerConnector) originServer.getConnectors()[0]).getLocalPort();
        proxyPort = ((ServerConnector) proxyServer.getConnectors()[0]).getLocalPort();
        originBaseUrl = "http://localhost:" + originPort;
        proxyBaseUrl = "http://localhost:" + proxyPort;

        System.out.println("========================================");
        System.out.println("Origin Proxy Integration Test");
        System.out.println("Origin Server: " + originBaseUrl);
        System.out.println("Proxy Server:  " + proxyBaseUrl);
        System.out.println("========================================");
    }

    @AfterAll
    static void stopServers() throws Exception {
        if (proxyServer != null) proxyServer.stop();
        if (originServer != null) originServer.stop();
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClients.createDefault();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpClient != null) httpClient.close();
    }

    private static Server createServer(Path storageDir, Path overrideXmlPath) throws Exception {
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

        Files.writeString(overrideXmlPath, overrideXml);
        ctx.setOverrideDescriptor(overrideXmlPath.toFile().getAbsolutePath());

        server.setHandler(ctx);
        return server;
    }

    // ==================== Setup Tests ====================

    @Test
    @Order(1)
    void shouldCreateBucketsOnBothServers() throws Exception {
        // Create source bucket on origin server
        HttpPut originPut = new HttpPut(originBaseUrl + "/source-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(originPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // Create proxy bucket on proxy server
        HttpPut proxyPut = new HttpPut(proxyBaseUrl + "/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(proxyPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(2)
    void shouldUploadFilesToOrigin() throws Exception {
        // Upload various files to origin server's source-bucket
        String[][] files = {
                {"hello.txt", "Hello from origin server!"},
                {"data/report.csv", "id,name\n1,alice\n2,bob"},
                {"images/photo.jpg", "fake-jpg-content"},
                {"docs/readme.md", "# Readme\nThis is from origin."}
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
            assertThat(body).contains("\"cachePolicy\":\"no-cache\"");
        }
    }

    // ==================== Proxy Behavior Tests ====================

    @Test
    @Order(4)
    void shouldProxyGetFromOriginWhenLocalMiss() throws Exception {
        // hello.txt exists on origin but not on proxy
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).isEqualTo("Hello from origin server!");
        }
    }

    @Test
    @Order(5)
    void shouldProxyNestedKeysFromOrigin() throws Exception {
        // data/report.csv — nested key on origin
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/data/report.csv");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).contains("alice");
            assertThat(body).contains("bob");
        }
    }

    @Test
    @Order(6)
    void shouldProxyHeadFromOrigin() throws Exception {
        HttpHead head = new HttpHead(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(head)) {
            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(resp.getHeader("ETag")).isNotNull();
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(7)
    void shouldReturnLocalObjectOverOrigin() throws Exception {
        // Upload a local file with the same key as an origin file
        HttpPut put = new HttpPut(proxyBaseUrl + "/proxy-bucket/hello.txt");
        put.setEntity(new StringEntity("Local override!", ContentType.TEXT_PLAIN));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // GET should return local content, not origin
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).isEqualTo("Local override!");
        }

        // Clean up so subsequent tests proxy again
        HttpDelete delete = new HttpDelete(proxyBaseUrl + "/proxy-bucket/hello.txt");
        httpClient.execute(delete).close();
    }

    @Test
    @Order(8)
    void shouldReturn404WhenNotOnOriginOrLocal() throws Exception {
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/does-not-exist.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(404);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(9)
    void shouldProxyWithPrefixFilter() throws Exception {
        // Update config: only proxy keys with prefix "images/"
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"source-bucket\",\"prefix\":\"images/\",\"cachePolicy\":\"no-cache\"}",
                originBaseUrl);

        HttpPut configPut = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        configPut.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(configPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // images/ key should proxy from origin
        HttpGet imageGet = new HttpGet(proxyBaseUrl + "/proxy-bucket/images/photo.jpg");
        try (CloseableHttpResponse resp = httpClient.execute(imageGet)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).isEqualTo("fake-jpg-content");
        }

        // Non-images key should NOT proxy → 404 (not on local, prefix doesn't match)
        HttpGet docGet = new HttpGet(proxyBaseUrl + "/proxy-bucket/docs/readme.md");
        try (CloseableHttpResponse resp = httpClient.execute(docGet)) {
            assertThat(resp.getCode()).isEqualTo(404);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(10)
    void shouldProxyWithQueryString() throws Exception {
        // Reset config to no prefix
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"source-bucket\",\"cachePolicy\":\"no-cache\"}",
                originBaseUrl);

        HttpPut configPut = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        configPut.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(configPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // Upload an object on origin
        HttpPut put = new HttpPut(originBaseUrl + "/source-bucket/test-obj");
        put.setEntity(new StringEntity("object content", ContentType.TEXT_PLAIN));
        try (CloseableHttpResponse resp = httpClient.execute(put)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // GET with ?acl — the proxy should forward the query string to origin
        HttpGet getAcl = new HttpGet(proxyBaseUrl + "/proxy-bucket/test-obj?acl");
        try (CloseableHttpResponse resp = httpClient.execute(getAcl)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // GET with ?tagging
        HttpGet getTagging = new HttpGet(proxyBaseUrl + "/proxy-bucket/test-obj?tagging");
        try (CloseableHttpResponse resp = httpClient.execute(getTagging)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }
    }

    @Test
    @Order(11)
    void shouldStopProxyingAfterConfigDeleted() throws Exception {
        // Delete the origin config
        HttpDelete delete = new HttpDelete(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(delete)) {
            assertThat(resp.getCode()).isEqualTo(204);
            EntityUtils.consume(resp.getEntity());
        }

        // Same key that previously proxied should now return 404
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/hello.txt");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(404);
            EntityUtils.consume(resp.getEntity());
        }
    }

    // ==================== Cross-Server Verification ====================

    @Test
    @Order(12)
    void shouldListObjectsOnOriginIndependently() throws Exception {
        // Verify origin server still has all its objects
        HttpGet listOrigin = new HttpGet(originBaseUrl + "/source-bucket");
        try (CloseableHttpResponse resp = httpClient.execute(listOrigin)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).contains("<Key>hello.txt</Key>");
            assertThat(body).contains("<Key>data/report.csv</Key>");
            assertThat(body).contains("<Key>images/photo.jpg</Key>");
            assertThat(body).contains("<Key>docs/readme.md</Key>");
        }
    }

    @Test
    @Order(13)
    void shouldReconfigureAndProxyAgain() throws Exception {
        // Re-add config to prove it works after deletion
        String configJson = String.format(
                "{\"originUrl\":\"%s\",\"originBucket\":\"source-bucket\",\"cachePolicy\":\"no-cache\"}",
                originBaseUrl);

        HttpPut configPut = new HttpPut(proxyBaseUrl + "/admin/origin-config/proxy-bucket");
        configPut.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse resp = httpClient.execute(configPut)) {
            assertThat(resp.getCode()).isEqualTo(200);
            EntityUtils.consume(resp.getEntity());
        }

        // Should proxy again
        HttpGet get = new HttpGet(proxyBaseUrl + "/proxy-bucket/docs/readme.md");
        try (CloseableHttpResponse resp = httpClient.execute(get)) {
            assertThat(resp.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(resp.getEntity());
            assertThat(body).contains("Readme");
            assertThat(body).contains("from origin");
        }
    }
}
