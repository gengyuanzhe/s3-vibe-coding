package org.example.integration;

import com.sun.net.httpserver.HttpServer;
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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for origin proxy feature.
 * Starts a real Jetty server and a mock upstream HTTP server.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OriginProxyIntegrationTest {

    private static Server server;
    private static int port;
    private static String baseUrl;

    private static HttpServer mockOrigin;
    private static int mockOriginPort;

    @TempDir
    static Path tempDir;
    private static Path testStorageDir;

    private CloseableHttpClient httpClient;

    @BeforeAll
    static void startServers() throws Exception {
        // --- Set up test storage directory ---
        testStorageDir = tempDir.resolve("storage");
        Files.createDirectories(testStorageDir);

        // --- Start mock origin HTTP server ---
        mockOrigin = HttpServer.create(new InetSocketAddress("localhost", 0), 0);

        // Handler: /origin-bucket/proxied.txt -> 200 "from origin"
        mockOrigin.createContext("/origin-bucket/proxied.txt", exchange -> {
            byte[] body = "from origin".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.getResponseHeaders().set("ETag", "origin-etag");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        // Handler: /origin-bucket/media/video.mp4 -> 200 "video content"
        mockOrigin.createContext("/origin-bucket/media/video.mp4", exchange -> {
            byte[] body = "video content".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        // Handler: /origin-bucket/missing.txt -> 404
        mockOrigin.createContext("/origin-bucket/missing.txt", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.getResponseBody().close();
        });

        // Handler: /origin-bucket/obj -> reflects query string in body
        mockOrigin.createContext("/origin-bucket/obj", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String bodyContent = query != null ? query : "";
            byte[] body = bodyContent.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        mockOrigin.start();
        mockOriginPort = mockOrigin.getAddress().getPort();

        // --- Start Jetty server ---
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        String webappPath = new File("src/main/webapp").getAbsolutePath();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWar(webappPath);
        webAppContext.setExtractWAR(false);

        // Build override web.xml with all servlets and auth config
        String overrideWebXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"https://jakarta.ee/xml/ns/jakartaee\n" +
                "         https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd\"\n" +
                "         version=\"6.0\">\n" +
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
                "        <init-param>\n" +
                "            <param-name>auth.mode</param-name>\n" +
                "            <param-value>both</param-value>\n" +
                "        </init-param>\n" +
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
        webAppContext.addOverrideDescriptor(overrideWebXmlPath.toFile().getAbsolutePath());

        server.setHandler(webAppContext);
        server.start();

        port = connector.getLocalPort();
        baseUrl = "http://localhost:" + port;

        System.out.println("========================================");
        System.out.println("Origin Proxy Integration Test");
        System.out.println("Jetty Port: " + port);
        System.out.println("Base URL: " + baseUrl);
        System.out.println("Mock Origin Port: " + mockOriginPort);
        System.out.println("Storage: " + testStorageDir);
        System.out.println("========================================");
    }

    @AfterAll
    static void stopServers() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (mockOrigin != null) {
            mockOrigin.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        httpClient = HttpClients.createDefault();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    // ==================== Helper Methods ====================

    private static String getOriginUrl() {
        return "http://localhost:" + mockOriginPort;
    }

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    void shouldCreateBucket() throws Exception {
        HttpPut request = new HttpPut(baseUrl + "/proxy-bucket");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
            EntityUtils.consume(response.getEntity());
        }
    }

    @Test
    @Order(2)
    void shouldConfigureOriginProxy() throws Exception {
        String configJson = "{\"originUrl\":\"" + getOriginUrl() + "\","
                + "\"originBucket\":\"origin-bucket\","
                + "\"cachePolicy\":\"no-cache\"}";

        HttpPut request = new HttpPut(baseUrl + "/admin/origin-config/proxy-bucket");
        request.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getHeader("Content-Type").getValue()).contains("application/json");

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("\"originUrl\":\"" + getOriginUrl() + "\"");
            assertThat(body).contains("\"originBucket\":\"origin-bucket\"");
            assertThat(body).contains("\"cachePolicy\":\"no-cache\"");
        }
    }

    @Test
    @Order(3)
    void shouldProxyNonExistentObject() throws Exception {
        // The file proxied.txt does not exist locally, so it should be fetched from origin
        HttpGet request = new HttpGet(baseUrl + "/proxy-bucket/proxied.txt");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).isEqualTo("from origin");
        }
    }

    @Test
    @Order(4)
    void shouldReturnLocalObjectWhenExists() throws Exception {
        // Upload a local file with the same key as a proxied object
        HttpPut uploadRequest = new HttpPut(baseUrl + "/proxy-bucket/proxied.txt");
        uploadRequest.setEntity(new StringEntity("local content", ContentType.TEXT_PLAIN));

        try (CloseableHttpResponse uploadResponse = httpClient.execute(uploadRequest)) {
            assertThat(uploadResponse.getCode()).isEqualTo(200);
            EntityUtils.consume(uploadResponse.getEntity());
        }

        // GET should return the local content, not the origin content
        HttpGet getRequest = new HttpGet(baseUrl + "/proxy-bucket/proxied.txt");
        try (CloseableHttpResponse getResponse = httpClient.execute(getRequest)) {
            assertThat(getResponse.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(getResponse.getEntity());
            assertThat(body).isEqualTo("local content");
        }

        // Clean up: delete the local file so subsequent tests proxy again
        HttpDelete deleteRequest = new HttpDelete(baseUrl + "/proxy-bucket/proxied.txt");
        try (CloseableHttpResponse deleteResponse = httpClient.execute(deleteRequest)) {
            EntityUtils.consume(deleteResponse.getEntity());
        }
    }

    @Test
    @Order(5)
    void shouldProxyHeadObject() throws Exception {
        HttpHead request = new HttpHead(baseUrl + "/proxy-bucket/proxied.txt");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
            // The origin sets ETag: origin-etag
            assertThat(response.getHeader("ETag")).isNotNull();
            assertThat(response.getHeader("ETag").getValue()).contains("origin-etag");
            EntityUtils.consume(response.getEntity());
        }
    }

    @Test
    @Order(6)
    void shouldProxyWithPrefixFilter() throws Exception {
        // Update config with prefix "media/"
        String configJson = "{\"originUrl\":\"" + getOriginUrl() + "\","
                + "\"originBucket\":\"origin-bucket\","
                + "\"prefix\":\"media/\","
                + "\"cachePolicy\":\"no-cache\"}";

        HttpPut configRequest = new HttpPut(baseUrl + "/admin/origin-config/proxy-bucket");
        configRequest.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse configResponse = httpClient.execute(configRequest)) {
            assertThat(configResponse.getCode()).isEqualTo(200);
            EntityUtils.consume(configResponse.getEntity());
        }

        // media/ key should proxy successfully
        HttpGet mediaRequest = new HttpGet(baseUrl + "/proxy-bucket/media/video.mp4");
        try (CloseableHttpResponse mediaResponse = httpClient.execute(mediaRequest)) {
            assertThat(mediaResponse.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(mediaResponse.getEntity());
            assertThat(body).isEqualTo("video content");
        }

        // Non-media key should get 404 (not proxied, no local file)
        HttpGet otherRequest = new HttpGet(baseUrl + "/proxy-bucket/proxied.txt");
        try (CloseableHttpResponse otherResponse = httpClient.execute(otherRequest)) {
            assertThat(otherResponse.getCode()).isEqualTo(404);
            EntityUtils.consume(otherResponse.getEntity());
        }
    }

    @Test
    @Order(7)
    void shouldProxyWithQueryString() throws Exception {
        // Reset config to no prefix
        String configJson = "{\"originUrl\":\"" + getOriginUrl() + "\","
                + "\"originBucket\":\"origin-bucket\","
                + "\"cachePolicy\":\"no-cache\"}";

        HttpPut configRequest = new HttpPut(baseUrl + "/admin/origin-config/proxy-bucket");
        configRequest.setEntity(new StringEntity(configJson, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse configResponse = httpClient.execute(configRequest)) {
            assertThat(configResponse.getCode()).isEqualTo(200);
            EntityUtils.consume(configResponse.getEntity());
        }

        // GET with query string - the mock origin handler echoes the query string as body
        HttpGet request = new HttpGet(baseUrl + "/proxy-bucket/obj?acl");
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("acl");
        }
    }

    @Test
    @Order(8)
    void shouldReturn404WhenOriginReturns404() throws Exception {
        HttpGet request = new HttpGet(baseUrl + "/proxy-bucket/missing.txt");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(404);
            EntityUtils.consume(response.getEntity());
        }
    }

    @Test
    @Order(9)
    void shouldDeleteOriginConfig() throws Exception {
        // Delete the origin config
        HttpDelete deleteRequest = new HttpDelete(baseUrl + "/admin/origin-config/proxy-bucket");
        try (CloseableHttpResponse deleteResponse = httpClient.execute(deleteRequest)) {
            assertThat(deleteResponse.getCode()).isEqualTo(204);
            EntityUtils.consume(deleteResponse.getEntity());
        }

        // Without origin config, requesting a non-existent key should return 404
        HttpGet getRequest = new HttpGet(baseUrl + "/proxy-bucket/proxied.txt");
        try (CloseableHttpResponse getResponse = httpClient.execute(getRequest)) {
            assertThat(getResponse.getCode()).isEqualTo(404);
            EntityUtils.consume(getResponse.getEntity());
        }
    }
}
