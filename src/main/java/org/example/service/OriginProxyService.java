package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for managing origin proxy configuration and proxying requests to origin S3 servers.
 */
public class OriginProxyService {

    private static final Logger logger = LoggerFactory.getLogger(OriginProxyService.class);
    private static final String CONFIG_FILE_NAME = ".origin-config.json";

    private final String storageRootDir;
    private final HttpClient httpClient;

    public OriginProxyService(String storageRootDir) {
        this.storageRootDir = storageRootDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Read origin proxy config for a bucket.
     *
     * @param bucketName the bucket name
     * @return the OriginConfig, or null if not configured or on error
     */
    public OriginConfig getOriginConfig(String bucketName) {
        Path configPath = getConfigPath(bucketName);
        if (!Files.exists(configPath)) {
            return null;
        }
        try {
            String json = Files.readString(configPath);
            return OriginConfig.fromJson(json);
        } catch (Exception e) {
            logger.warn("Failed to read origin config for bucket: {}", bucketName, e);
            return null;
        }
    }

    /**
     * Save origin proxy config for a bucket.
     *
     * @param bucketName the bucket name
     * @param config     the config to save
     * @throws RuntimeException if writing fails
     */
    public void saveOriginConfig(String bucketName, OriginConfig config) {
        Path configPath = getConfigPath(bucketName);
        try {
            Files.writeString(configPath, config.toJson());
            logger.info("Saved origin config for bucket: {}", bucketName);
        } catch (Exception e) {
            logger.error("Failed to save origin config for bucket: {}", bucketName, e);
            throw new RuntimeException("Failed to save origin config for bucket: " + bucketName, e);
        }
    }

    /**
     * Delete origin proxy config for a bucket. No-op if not configured.
     *
     * @param bucketName the bucket name
     */
    public void deleteOriginConfig(String bucketName) {
        Path configPath = getConfigPath(bucketName);
        try {
            Files.deleteIfExists(configPath);
        } catch (Exception e) {
            logger.warn("Failed to delete origin config for bucket: {}", bucketName, e);
        }
    }

    private Path getConfigPath(String bucketName) {
        return Paths.get(storageRootDir, bucketName, CONFIG_FILE_NAME);
    }

    // ── Proxy execution ──────────────────────────────────────────────

    /**
     * Result of a proxied request to an origin server.
     */
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

        public int getStatusCode() {
            return statusCode;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public byte[] getBody() {
            return body;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /**
     * Proxy a request to the configured origin for the given bucket.
     *
     * @param bucketName  the bucket name
     * @param objectKey   the object key
     * @param method      the HTTP method (GET, HEAD, etc.)
     * @param queryString the query string (may be null)
     * @return a ProxyResult, or null if no origin config or prefix does not match
     */
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
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

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

    /**
     * Build the upstream URL from config, object key, and optional query string.
     */
    String buildUpstreamUrl(OriginConfig config, String objectKey, String queryString) {
        String originUrl = config.getOriginUrl();
        if (originUrl.endsWith("/")) {
            originUrl = originUrl.substring(0, originUrl.length() - 1);
        }
        String url = originUrl + "/" + config.getOriginBucket() + "/" + objectKey;
        if (queryString != null && !queryString.isEmpty()) {
            url = url + "?" + queryString;
        }
        return url;
    }

    /**
     * Extract response headers into a plain Map (last value wins for duplicates).
     */
    Map<String, String> extractHeaders(HttpHeaders responseHeaders) {
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : responseHeaders.map().entrySet()) {
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                headers.put(entry.getKey(), values.get(values.size() - 1));
            }
        }
        return headers;
    }
}
