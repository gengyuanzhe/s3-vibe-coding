package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

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
}
