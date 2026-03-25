package org.example.service;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Storage Service for managing file operations similar to S3
 */
public class StorageService {
    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);

    private final String storageRootDir;
    private final long maxFileSize;

    public StorageService(String storageRootDir, long maxFileSize) {
        this.storageRootDir = storageRootDir;
        this.maxFileSize = maxFileSize;
        initializeStorageDirectory();
    }

    /**
     * Initialize the storage root directory
     */
    private void initializeStorageDirectory() {
        File rootDir = new File(storageRootDir);
        if (!rootDir.exists()) {
            if (rootDir.mkdirs()) {
                logger.info("Created storage directory: {}", storageRootDir);
            } else {
                logger.error("Failed to create storage directory: {}", storageRootDir);
                throw new RuntimeException("Failed to create storage directory");
            }
        }
    }

    /**
     * Get full path for a given bucket and key
     */
    private Path getFilePath(String bucketName, String objectKey) {
        String sanitizedBucket = sanitizePathComponent(bucketName);
        String sanitizedKey = sanitizePathKey(objectKey);
        return Paths.get(storageRootDir, sanitizedBucket, sanitizedKey);
    }

    /**
     * Sanitize bucket name to prevent path traversal
     */
    private String sanitizePathComponent(String component) {
        if (component == null || component.isEmpty()) {
            return component;
        }
        return component.replaceAll("[\\\\/\\s]", "_");
    }

    /**
     * Sanitize object key while preserving directory structure
     */
    private String sanitizePathKey(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        // Remove path traversal attempts
        String sanitized = key.replace("..", "");
        return sanitized;
    }

    /**
     * Check if bucket exists
     */
    public boolean bucketExists(String bucketName) {
        File bucketDir = Paths.get(storageRootDir, sanitizePathComponent(bucketName)).toFile();
        return bucketDir.exists() && bucketDir.isDirectory();
    }

    /**
     * Create a new bucket
     */
    public boolean createBucket(String bucketName) {
        String sanitized = sanitizePathComponent(bucketName);
        File bucketDir = Paths.get(storageRootDir, sanitized).toFile();

        if (bucketDir.exists()) {
            return false;
        }

        return bucketDir.mkdirs();
    }

    /**
     * List all buckets
     */
    public List<String> listBuckets() {
        File rootDir = new File(storageRootDir);
        File[] buckets = rootDir.listFiles(File::isDirectory);

        if (buckets == null) {
            return Collections.emptyList();
        }

        List<String> bucketNames = new ArrayList<>();
        for (File bucket : buckets) {
            bucketNames.add(bucket.getName());
        }
        return bucketNames;
    }

    /**
     * Delete a bucket (must be empty)
     */
    public boolean deleteBucket(String bucketName) {
        File bucketDir = Paths.get(storageRootDir, sanitizePathComponent(bucketName)).toFile();

        if (!bucketDir.exists()) {
            return false;
        }

        String[] files = bucketDir.list();
        if (files != null && files.length > 0) {
            return false;
        }

        return bucketDir.delete();
    }

    /**
     * Upload an object to a bucket
     */
    public boolean putObject(String bucketName, String objectKey, InputStream inputStream, long contentLength) {
        if (contentLength > maxFileSize) {
            logger.warn("File size {} exceeds maximum allowed size {}", contentLength, maxFileSize);
            return false;
        }

        if (!bucketExists(bucketName)) {
            logger.warn("Bucket does not exist: {}", bucketName);
            return false;
        }

        Path filePath = getFilePath(bucketName, objectKey);
        File parentDir = filePath.getParent().toFile();

        if (!parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                logger.error("Failed to create directory: {}", parentDir);
                return false;
            }
        }

        try {
            Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Uploaded object: {}/{} (size: {} bytes)", bucketName, objectKey, contentLength);
            return true;
        } catch (IOException e) {
            logger.error("Failed to upload object: {}/{}", bucketName, objectKey, e);
            return false;
        }
    }

    /**
     * Get an object from a bucket
     */
    public File getObject(String bucketName, String objectKey) {
        if (!bucketExists(bucketName)) {
            return null;
        }

        Path filePath = getFilePath(bucketName, objectKey);
        File file = filePath.toFile();

        if (file.exists() && file.isFile()) {
            return file;
        }
        return null;
    }

    /**
     * List objects in a bucket
     */
    public List<ObjectMetadata> listObjects(String bucketName, String prefix) {
        List<ObjectMetadata> objects = new ArrayList<>();

        if (!bucketExists(bucketName)) {
            return objects;
        }

        File bucketDir = Paths.get(storageRootDir, sanitizePathComponent(bucketName)).toFile();

        try (Stream<Path> paths = Files.walk(bucketDir.toPath())) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            String relativePath = bucketDir.toPath().relativize(path).toString();
                            if (prefix == null || relativePath.startsWith(prefix)) {
                                ObjectMetadata metadata = new ObjectMetadata();
                                metadata.setKey(relativePath.replace(File.separatorChar, '/'));
                                metadata.setSize(path.toFile().length());
                                metadata.setLastModified(Files.getLastModifiedTime(path).toMillis());
                                objects.add(metadata);
                            }
                        } catch (IOException e) {
                            logger.warn("Failed to get metadata for file: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            logger.error("Failed to list objects in bucket: {}", bucketName, e);
        }

        return objects;
    }

    /**
     * Delete an object
     */
    public boolean deleteObject(String bucketName, String objectKey) {
        if (!bucketExists(bucketName)) {
            return false;
        }

        Path filePath = getFilePath(bucketName, objectKey);
        File file = filePath.toFile();

        if (file.exists() && file.isFile()) {
            if (file.delete()) {
                logger.info("Deleted object: {}/{}", bucketName, objectKey);
                return true;
            }
        }
        return false;
    }

    /**
     * Get object metadata
     */
    public ObjectMetadata getObjectMetadata(String bucketName, String objectKey) {
        File file = getObject(bucketName, objectKey);
        if (file != null) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setKey(objectKey);
            metadata.setSize(file.length());
            metadata.setLastModified(file.lastModified());
            metadata.setContentType(guessContentType(objectKey));
            return metadata;
        }
        return null;
    }

    /**
     * Guess content type based on file extension
     */
    private String guessContentType(String filename) {
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "html" -> "text/html";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    /**
     * Object metadata class
     */
    public static class ObjectMetadata {
        private String key;
        private long size;
        private long lastModified;
        private String contentType;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public long getLastModified() {
            return lastModified;
        }

        public void setLastModified(long lastModified) {
            this.lastModified = lastModified;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public String getLastModifiedFormatted() {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneId.of("UTC"));
            return formatter.format(Instant.ofEpochMilli(lastModified));
        }
    }
}
