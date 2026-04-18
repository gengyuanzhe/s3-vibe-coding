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
 * Integration tests for S3 Storage Service
 * Starts a real Jetty server and tests all API endpoints
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3StorageIntegrationTest {

    private static Server server;
    private static int port;
    private static String baseUrl;
    private static String apiUrl;
    private static Path testStorageDir;

    @TempDir
    static Path tempDir;

    private CloseableHttpClient httpClient;

    @BeforeAll
    static void startServer() throws Exception {
        // Create test storage directory
        testStorageDir = tempDir.resolve("storage");
        Files.createDirectories(testStorageDir);

        // Find available port
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0); // Use random available port
        server.addConnector(connector);

        // Create webapp context
        String webappPath = new File("src/main/webapp").getAbsolutePath();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWar(webappPath);
        webAppContext.setExtractWAR(false);

        // Create override web.xml to set custom storage directory
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
                "            <param-value>none</param-value>\n" +
                "        </init-param>\n" +
                "    </filter>\n" +
                "    <filter-mapping>\n" +
                "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n" +
                "        <url-pattern>/*</url-pattern>\n" +
                "    </filter-mapping>\n" +
                "</web-app>";

        Path overrideWebXmlPath = tempDir.resolve("override-web.xml");
        Files.writeString(overrideWebXmlPath, overrideWebXml);
        webAppContext.setOverrideDescriptor(overrideWebXmlPath.toFile().getAbsolutePath());

        server.setHandler(webAppContext);
        server.start();

        port = connector.getLocalPort();
        baseUrl = "http://localhost:" + port;
        apiUrl = baseUrl;

        System.out.println("========================================");
        System.out.println("Integration Test Server Started");
        System.out.println("Port: " + port);
        System.out.println("API URL: " + apiUrl);
        System.out.println("Storage: " + testStorageDir);
        System.out.println("========================================");
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
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

    // ==================== Health Check Tests ====================

    @Test
    @Order(1)
    void healthCheck_shouldReturnOk() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/health");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getHeader("Content-Type").getValue()).contains("application/json");

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("\"status\":\"ok\"");
            assertThat(body).contains("\"service\":\"s3-storage\"");
        }
    }

    // ==================== Bucket Operations ====================

    @Test
    @Order(10)
    void listBuckets_whenEmpty_shouldReturnEmptyList() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getHeader("Content-Type").getValue()).contains("application/xml");

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<ListAllMyBucketsResult");
            assertThat(body).contains("<Buckets>");
        }
    }

    @Test
    @Order(11)
    void createBucket_shouldSucceed() throws Exception {
        HttpPut request = new HttpPut(apiUrl + "/test-bucket");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
            EntityUtils.consume(response.getEntity());
        }
    }

    @Test
    @Order(12)
    void createBucket_whenExists_shouldReturn409() throws Exception {
        HttpPut request = new HttpPut(apiUrl + "/test-bucket");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(409);
        }
    }

    @Test
    @Order(13)
    void listBuckets_afterCreate_shouldContainBucket() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<Name>test-bucket</Name>");
        }
    }

    @Test
    @Order(14)
    void createMultipleBuckets() throws Exception {
        for (int i = 1; i <= 3; i++) {
            HttpPut request = new HttpPut(apiUrl + "/bucket-" + i);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                assertThat(response.getCode()).isEqualTo(200);
            }
        }

        // Verify all buckets exist
        HttpGet listRequest = new HttpGet(apiUrl + "/");
        try (CloseableHttpResponse response = httpClient.execute(listRequest)) {
            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<Name>bucket-1</Name>");
            assertThat(body).contains("<Name>bucket-2</Name>");
            assertThat(body).contains("<Name>bucket-3</Name>");
        }
    }

    // ==================== Object Operations ====================

    @Test
    @Order(20)
    void listObjects_emptyBucket_shouldReturnEmpty() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/test-bucket");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<ListBucketResult");
            assertThat(body).contains("<Name>test-bucket</Name>");
        }
    }

    @Test
    @Order(21)
    void uploadObject_shouldSucceed() throws Exception {
        HttpPut request = new HttpPut(apiUrl + "/test-bucket/hello.txt");
        request.setEntity(new StringEntity("Hello, World!", ContentType.TEXT_PLAIN));

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getHeader("ETag")).isNotNull();
            EntityUtils.consume(response.getEntity());
        }
    }

    @Test
    @Order(22)
    void downloadObject_shouldReturnContent() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/test-bucket/hello.txt");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).isEqualTo("Hello, World!");
            assertThat(response.getHeader("Last-Modified")).isNotNull();
            assertThat(response.getHeader("ETag")).isNotNull();
        }
    }

    @Test
    @Order(23)
    void listObjects_afterUpload_shouldContainObject() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/test-bucket");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<Key>hello.txt</Key>");
            assertThat(body).contains("<Size>13</Size>");
            assertThat(body).contains("<ETag>");
        }
    }

    @Test
    @Order(24)
    void uploadObject_withNestedPath_shouldSucceed() throws Exception {
        HttpPut request = new HttpPut(apiUrl + "/test-bucket/folder/subfolder/data.json");
        String jsonContent = "{\"name\":\"test\",\"value\":123}";
        request.setEntity(new StringEntity(jsonContent, ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
        }

        // Verify download
        HttpGet getRequest = new HttpGet(apiUrl + "/test-bucket/folder/subfolder/data.json");
        try (CloseableHttpResponse getResponse = httpClient.execute(getRequest)) {
            assertThat(getResponse.getCode()).isEqualTo(200);
            String body = EntityUtils.toString(getResponse.getEntity());
            assertThat(body).isEqualTo(jsonContent);
        }
    }

    @Test
    @Order(25)
    void listObjects_withPrefix_shouldFilter() throws Exception {
        // Upload more files
        uploadFile("test-bucket", "docs/readme.md", "# Readme");
        uploadFile("test-bucket", "docs/guide.md", "# Guide");
        uploadFile("test-bucket", "images/logo.png", "fake-png-data");

        // List with prefix
        HttpGet request = new HttpGet(apiUrl + "/test-bucket?prefix=docs/");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<Prefix>docs/</Prefix>");
            assertThat(body).contains("<Key>docs/readme.md</Key>");
            assertThat(body).contains("<Key>docs/guide.md</Key>");
            assertThat(body).doesNotContain("<Key>hello.txt</Key>");
            assertThat(body).doesNotContain("<Key>images/logo.png</Key>");
        }
    }

    @Test
    @Order(26)
    void downloadObject_nonExistent_shouldReturn404() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/test-bucket/non-existent.txt");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(404);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<Error");
            assertThat(body).contains("<Code>NoSuchKey</Code>");
        }
    }

    @Test
    @Order(27)
    void uploadObject_toNonExistentBucket_shouldReturn404() throws Exception {
        HttpPut request = new HttpPut(apiUrl + "/non-existent-bucket/file.txt");
        request.setEntity(new StringEntity("content", ContentType.TEXT_PLAIN));

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(404);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<Error");
            assertThat(body).contains("<Code>NoSuchBucket</Code>");
        }
    }

    @Test
    @Order(28)
    void deleteObject_shouldSucceed() throws Exception {
        // Upload a file to delete
        uploadFile("test-bucket", "to-delete.txt", "delete me");

        // Delete
        HttpDelete request = new HttpDelete(apiUrl + "/test-bucket/to-delete.txt");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(204);
        }

        // Verify deleted
        HttpGet getRequest = new HttpGet(apiUrl + "/test-bucket/to-delete.txt");
        try (CloseableHttpResponse getResponse = httpClient.execute(getRequest)) {
            assertThat(getResponse.getCode()).isEqualTo(404);
        }
    }

    @Test
    @Order(29)
    void deleteObject_nonExistent_shouldReturn404() throws Exception {
        HttpDelete request = new HttpDelete(apiUrl + "/test-bucket/non-existent-file.txt");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(404);
        }
    }

    // ==================== Bucket Delete Operations ====================

    @Test
    @Order(30)
    void deleteBucket_nonEmpty_shouldReturn409() throws Exception {
        // Create a bucket with files for this test
        HttpPut createRequest = new HttpPut(apiUrl + "/non-empty-bucket");
        try (CloseableHttpResponse response = httpClient.execute(createRequest)) {
            assertThat(response.getCode()).isEqualTo(200);
        }

        // Upload a file
        uploadFile("non-empty-bucket", "file.txt", "content");

        // Try to delete non-empty bucket - should fail
        HttpDelete request = new HttpDelete(apiUrl + "/non-empty-bucket");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(409);
        }
    }

    @Test
    @Order(31)
    void deleteBucket_afterEmptying_shouldSucceed() throws Exception {
        // Create a bucket with files for this test
        HttpPut createRequest = new HttpPut(apiUrl + "/to-delete-bucket");
        try (CloseableHttpResponse response = httpClient.execute(createRequest)) {
            assertThat(response.getCode()).isEqualTo(200);
        }

        // Upload files
        uploadFile("to-delete-bucket", "file1.txt", "content1");
        uploadFile("to-delete-bucket", "file2.txt", "content2");

        // Delete all objects in bucket
        deleteAllObjectsInBucket("to-delete-bucket");

        // Now delete bucket
        HttpDelete request = new HttpDelete(apiUrl + "/to-delete-bucket");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(204);
        }

        // Verify bucket is gone
        HttpGet getRequest = new HttpGet(apiUrl + "/");
        try (CloseableHttpResponse getResponse = httpClient.execute(getRequest)) {
            String body = EntityUtils.toString(getResponse.getEntity());
            assertThat(body).doesNotContain("<Name>to-delete-bucket</Name>");
        }
    }

    @Test
    @Order(32)
    void deleteBucket_nonExistent_shouldReturn404() throws Exception {
        HttpDelete request = new HttpDelete(apiUrl + "/non-existent-bucket");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(404);
        }
    }

    // ==================== Error Handling Tests ====================

    @Test
    @Order(50)
    void listObjects_nonExistentBucket_shouldReturn404() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/non-existent-bucket");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(404);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<Error");
            assertThat(body).contains("<Code>NoSuchBucket</Code>");
        }
    }

    @Test
    @Order(51)
    void deleteObject_nonExistentBucket_shouldReturn404() throws Exception {
        HttpDelete request = new HttpDelete(apiUrl + "/non-existent-bucket/file.txt");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(404);
        }
    }

    // ==================== Web UI Tests ====================

    @Test
    @Order(60)
    void webUI_shouldBeAccessible() throws Exception {
        HttpGet request = new HttpGet(baseUrl + "/index.html");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("<!DOCTYPE html>");
            assertThat(body).contains("S3-Like Storage Service");
        }
    }

    // ==================== Admin Auth Endpoint Tests ====================

    @Test
    @Order(70)
    void getAuthStatus_shouldReturnCurrentMode() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/admin/auth-status");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getHeader("Content-Type").getValue()).contains("application/json");

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("\"mode\"");
        }
    }

    @Test
    @Order(71)
    void setAuthStatus_shouldUpdateMode() throws Exception {
        // Set to both
        HttpPost setRequest = new HttpPost(apiUrl + "/admin/auth-status");
        setRequest.setEntity(new StringEntity("{\"mode\":\"both\"}", ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(setRequest)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("\"mode\":\"both\"");
        }

        // Verify via GET
        HttpGet getRequest = new HttpGet(apiUrl + "/admin/auth-status");
        try (CloseableHttpResponse response = httpClient.execute(getRequest)) {
            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("\"mode\":\"both\"");
        }

        // Reset to none for subsequent tests
        HttpPost resetRequest = new HttpPost(apiUrl + "/admin/auth-status");
        resetRequest.setEntity(new StringEntity("{\"mode\":\"none\"}", ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse response = httpClient.execute(resetRequest)) {
            EntityUtils.consume(response.getEntity());
        }
    }

    // ==================== Helper Methods ====================

    private void uploadFile(String bucket, String key, String content) throws Exception {
        HttpPut request = new HttpPut(apiUrl + "/" + bucket + "/" + key);
        request.setEntity(new StringEntity(content, ContentType.TEXT_PLAIN));
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
        }
    }

    private void deleteAllObjectsInBucket(String bucket) throws Exception {
        // List all objects
        HttpGet listRequest = new HttpGet(apiUrl + "/" + bucket);
        try (CloseableHttpResponse response = httpClient.execute(listRequest)) {
            String body = EntityUtils.toString(response.getEntity());

            // Extract keys and delete each
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<Key>([^<]+)</Key>");
            java.util.regex.Matcher matcher = pattern.matcher(body);

            while (matcher.find()) {
                String key = matcher.group(1);
                HttpDelete deleteRequest = new HttpDelete(apiUrl + "/" + bucket + "/" + key);
                try (CloseableHttpResponse deleteResponse = httpClient.execute(deleteRequest)) {
                    // Ignore response
                }
            }
        }
    }
}
