package org.example.unit;

import org.example.service.OriginConfig;
import org.example.service.OriginProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OriginProxyService config CRUD operations
 */
class OriginProxyServiceTest {

    @TempDir
    Path tempDir;

    private OriginProxyService originProxyService;

    @BeforeEach
    void setUp() {
        originProxyService = new OriginProxyService(tempDir.toString());
    }

    private void createBucket(String name) throws Exception {
        Path bucketDir = tempDir.resolve(name);
        Files.createDirectories(bucketDir);
        Files.writeString(bucketDir.resolve(".bucket-created"), String.valueOf(System.currentTimeMillis()));
    }

    @Test
    void getOriginConfig_returnsNullWhenNotConfigured() throws Exception {
        // Given
        createBucket("test-bucket");

        // When
        OriginConfig config = originProxyService.getOriginConfig("test-bucket");

        // Then
        assertThat(config).isNull();
    }

    @Test
    void saveOriginConfig_persistsAndReadBack() throws Exception {
        // Given
        createBucket("test-bucket");
        OriginConfig.Credentials creds = new OriginConfig.Credentials("AKID123", "secret456");
        OriginConfig config = new OriginConfig(
                "https://origin.example.com",
                "origin-bucket",
                "prefix/",
                "cache",
                creds,
                null, null
        );

        // When
        originProxyService.saveOriginConfig("test-bucket", config);
        OriginConfig loaded = originProxyService.getOriginConfig("test-bucket");

        // Then
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOriginUrl()).isEqualTo("https://origin.example.com");
        assertThat(loaded.getOriginBucket()).isEqualTo("origin-bucket");
        assertThat(loaded.getPrefix()).isEqualTo("prefix/");
        assertThat(loaded.getCachePolicy()).isEqualTo("cache");
        assertThat(loaded.getCredentials()).isNotNull();
        assertThat(loaded.getCredentials().accessKey()).isEqualTo("AKID123");
        assertThat(loaded.getCredentials().secretKey()).isEqualTo("secret456");
    }

    @Test
    void saveOriginConfig_overwritesExistingConfig() throws Exception {
        // Given
        createBucket("test-bucket");
        OriginConfig first = new OriginConfig(
                "https://first.example.com",
                "first-bucket",
                null,
                "no-cache",
                null,
                null, null
        );
        OriginConfig second = new OriginConfig(
                "https://second.example.com",
                "second-bucket",
                "docs/",
                "cache-ttl",
                new OriginConfig.Credentials("newKey", "newSecret"),
                null, null
        );

        // When
        originProxyService.saveOriginConfig("test-bucket", first);
        originProxyService.saveOriginConfig("test-bucket", second);
        OriginConfig loaded = originProxyService.getOriginConfig("test-bucket");

        // Then
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOriginUrl()).isEqualTo("https://second.example.com");
        assertThat(loaded.getOriginBucket()).isEqualTo("second-bucket");
        assertThat(loaded.getPrefix()).isEqualTo("docs/");
        assertThat(loaded.getCachePolicy()).isEqualTo("cache-ttl");
        assertThat(loaded.getCredentials().accessKey()).isEqualTo("newKey");
        assertThat(loaded.getCredentials().secretKey()).isEqualTo("newSecret");
    }

    @Test
    void deleteOriginConfig_removesConfig() throws Exception {
        // Given
        createBucket("test-bucket");
        OriginConfig config = new OriginConfig(
                "https://origin.example.com", "origin-bucket", null, "cache", null, null, null
        );
        originProxyService.saveOriginConfig("test-bucket", config);
        assertThat(originProxyService.getOriginConfig("test-bucket")).isNotNull();

        // When
        originProxyService.deleteOriginConfig("test-bucket");

        // Then
        assertThat(originProxyService.getOriginConfig("test-bucket")).isNull();
    }

    @Test
    void deleteOriginConfig_doesNotThrowWhenNotConfigured() throws Exception {
        // Given
        createBucket("test-bucket");

        // When / Then - no exception thrown
        originProxyService.deleteOriginConfig("test-bucket");
    }

    @Test
    void configFileIsInsideBucketDir() throws Exception {
        // Given
        createBucket("test-bucket");
        OriginConfig config = new OriginConfig(
                "https://origin.example.com", "origin-bucket", "pfx/", "cache",
                new OriginConfig.Credentials("ak", "sk"), null, null
        );

        // When
        originProxyService.saveOriginConfig("test-bucket", config);

        // Then - config file exists at the expected path inside the bucket dir
        Path configFile = tempDir.resolve("test-bucket").resolve(".origin-config.json");
        assertThat(configFile).exists();

        // Verify the content is valid JSON that can be parsed back
        String json = Files.readString(configFile);
        OriginConfig parsed = OriginConfig.fromJson(json);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getOriginUrl()).isEqualTo("https://origin.example.com");
    }

    // ── Proxy execution tests ────────────────────────────────────────

    @Test
    void proxyRequest_shouldForwardGetObjectToOrigin() throws Exception {
        // Given - mock upstream server
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/origin-bucket/test-key.txt", exchange -> {
            byte[] body = "hello from origin".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            createBucket("test-bucket");
            OriginConfig config = new OriginConfig(
                    "http://127.0.0.1:" + port, "origin-bucket", null, "cache", null, null, null
            );
            originProxyService.saveOriginConfig("test-bucket", config);

            // When
            OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                    "test-bucket", "test-key.txt", "GET", null
            );

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getBody()).isEqualTo("hello from origin".getBytes(StandardCharsets.UTF_8));
            assertThat(result.getHeaders()).containsEntry("content-type", "text/plain");
            assertThat(result.getErrorCode()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void proxyRequest_shouldReturnNullWhenNoConfig() throws Exception {
        // Given - bucket exists but has no origin config
        createBucket("test-bucket");

        // When
        OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                "test-bucket", "any-key.txt", "GET", null
        );

        // Then
        assertThat(result).isNull();
    }

    @Test
    void proxyRequest_shouldReturnNullWhenPrefixDoesNotMatch() throws Exception {
        // Given
        createBucket("test-bucket");
        OriginConfig config = new OriginConfig(
                "http://127.0.0.1:1", "origin-bucket", "media/", "cache", null, null, null
        );
        originProxyService.saveOriginConfig("test-bucket", config);

        // When
        OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                "test-bucket", "docs/readme.txt", "GET", null
        );

        // Then
        assertThat(result).isNull();
    }

    @Test
    void proxyRequest_shouldReturn502OnUpstreamError() throws Exception {
        // Given - config points to localhost:1 where nothing is listening
        createBucket("test-bucket");
        OriginConfig config = new OriginConfig(
                "http://127.0.0.1:1", "origin-bucket", null, "cache", null, null, null
        );
        originProxyService.saveOriginConfig("test-bucket", config);

        // When
        OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                "test-bucket", "test-key.txt", "GET", null
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(502);
        assertThat(result.getErrorCode()).isEqualTo("OriginError");
        assertThat(result.getBody()).isNull();
    }

    @Test
    void proxyRequest_shouldPassthroughUpstream404() throws Exception {
        // Given - mock upstream returns 404
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/origin-bucket/missing-key.txt", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            createBucket("test-bucket");
            OriginConfig config = new OriginConfig(
                    "http://127.0.0.1:" + port, "origin-bucket", null, "cache", null, null, null
            );
            originProxyService.saveOriginConfig("test-bucket", config);

            // When
            OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                    "test-bucket", "missing-key.txt", "GET", null
            );

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(404);
            assertThat(result.getErrorCode()).isEqualTo("NoSuchKey");
            assertThat(result.getBody()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void proxyRequest_shouldHandleHeadRequest() throws Exception {
        // Given - mock upstream returns 200 with Content-Length but no body for HEAD
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/origin-bucket/test-key.txt", exchange -> {
            exchange.getResponseHeaders().set("Content-Length", "42");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            createBucket("test-bucket");
            OriginConfig config = new OriginConfig(
                    "http://127.0.0.1:" + port, "origin-bucket", null, "cache", null, null, null
            );
            originProxyService.saveOriginConfig("test-bucket", config);

            // When
            OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                    "test-bucket", "test-key.txt", "HEAD", null
            );

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getBody()).isNull();
            assertThat(result.getHeaders()).containsEntry("content-length", "42");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void proxyRequest_shouldSignRequestWhenCredentialsConfigured() throws Exception {
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

            OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                    "test-bucket", "secret-file.txt", "GET", null
            );

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getBody()).isEqualTo("secret content".getBytes(StandardCharsets.UTF_8));
            assertThat(receivedAuthHeader[0]).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void proxyRequest_shouldStillProxyWithoutSigningWhenNoCredentials() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/origin-bucket/public-file.txt", exchange -> {
            byte[] body = "public content".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            createBucket("test-bucket");
            OriginConfig config = new OriginConfig(
                    "http://127.0.0.1:" + port, "origin-bucket", null, "no-cache",
                    null, null, null
            );
            originProxyService.saveOriginConfig("test-bucket", config);

            OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                    "test-bucket", "public-file.txt", "GET", null
            );

            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(result.getBody()).isEqualTo("public content".getBytes(StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void proxyRequest_shouldForwardQueryString() throws Exception {
        // Given - mock upstream echoes the query string back in the response body
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/origin-bucket/test-key.txt", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            byte[] body = (query != null ? query : "").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            createBucket("test-bucket");
            OriginConfig config = new OriginConfig(
                    "http://127.0.0.1:" + port, "origin-bucket", null, "cache", null, null, null
            );
            originProxyService.saveOriginConfig("test-bucket", config);

            // When
            OriginProxyService.ProxyResult result = originProxyService.proxyRequest(
                    "test-bucket", "test-key.txt", "GET", "prefix=folder/&max-keys=10"
            );

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getStatusCode()).isEqualTo(200);
            assertThat(new String(result.getBody(), StandardCharsets.UTF_8))
                    .isEqualTo("prefix=folder/&max-keys=10");
        } finally {
            server.stop(0);
        }
    }
}
