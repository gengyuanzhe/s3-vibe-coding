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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
     * Clean up empty parent directories after deleting an object.
     * Walks up from the deleted file's parent, stopping at the bucket directory.
     */
    private void cleanupEmptyParentDirs(Path filePath, String bucketName) {
        Path bucketPath = Paths.get(storageRootDir, sanitizePathComponent(bucketName));
        Path parent = filePath.getParent();
        while (parent != null && !parent.equals(bucketPath)) {
            File dir = parent.toFile();
            if (dir.isDirectory()) {
                String[] contents = dir.list();
                if (contents == null || contents.length == 0) {
                    dir.delete();
                } else {
                    break;
                }
            } else {
                break;
            }
            parent = parent.getParent();
        }
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

        if (bucketDir.mkdirs()) {
            try {
                Path createdFile = Paths.get(storageRootDir, sanitized, ".bucket-created");
                Files.writeString(createdFile, String.valueOf(System.currentTimeMillis()));
            } catch (IOException e) {
                logger.warn("Failed to write bucket creation time", e);
            }
            return true;
        }
        return false;
    }

    /**
     * List all buckets
     */
    public List<BucketInfo> listBuckets() {
        File rootDir = new File(storageRootDir);
        File[] buckets = rootDir.listFiles(File::isDirectory);

        if (buckets == null) {
            return Collections.emptyList();
        }

        List<BucketInfo> bucketInfos = new ArrayList<>();
        for (File bucket : buckets) {
            long creationTime = getBucketCreationTime(bucket.getName());
            bucketInfos.add(new BucketInfo(bucket.getName(), creationTime));
        }
        return bucketInfos;
    }

    /**
     * Get bucket creation timestamp
     */
    public long getBucketCreationTime(String bucketName) {
        String sanitized = sanitizePathComponent(bucketName);
        Path createdFile = Paths.get(storageRootDir, sanitized, ".bucket-created");
        if (Files.exists(createdFile)) {
            try {
                return Long.parseLong(Files.readString(createdFile).trim());
            } catch (Exception ignored) {
            }
        }
        File bucketDir = Paths.get(storageRootDir, sanitized).toFile();
        return bucketDir.exists() ? bucketDir.lastModified() : System.currentTimeMillis();
    }

    /**
     * Delete a bucket (must be empty)
     */
    public boolean deleteBucket(String bucketName) {
        File bucketDir = Paths.get(storageRootDir, sanitizePathComponent(bucketName)).toFile();

        if (!bucketDir.exists()) {
            return false;
        }

        // Check for user files (ignore hidden/sidecar files starting with .)
        String[] files = bucketDir.list();
        if (files != null) {
            long userFiles = Arrays.stream(files)
                    .filter(f -> !f.startsWith("."))
                    .count();
            if (userFiles > 0) {
                return false;
            }
        }

        // Delete all hidden/sidecar files first, then the directory
        if (files != null) {
            for (String file : files) {
                new File(bucketDir, file).delete();
            }
        }
        return bucketDir.delete();
    }

    /**
     * Upload an object to a bucket. Returns MD5 ETag on success, null on failure.
     */
    public String putObject(String bucketName, String objectKey, InputStream inputStream, long contentLength) {
        if (contentLength > maxFileSize) {
            logger.warn("File size {} exceeds maximum allowed size {}", contentLength, maxFileSize);
            return null;
        }

        if (!bucketExists(bucketName)) {
            logger.warn("Bucket does not exist: {}", bucketName);
            return null;
        }

        Path filePath = getFilePath(bucketName, objectKey);
        File parentDir = filePath.getParent().toFile();

        if (!parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                logger.error("Failed to create directory: {}", parentDir);
                return null;
            }
        }

        try {
            byte[] data = inputStream.readAllBytes();
            Files.write(filePath, data);

            String etag = computeMd5Hex(data);

            Path etagFile = Paths.get(filePath.toString() + ".etag");
            Files.writeString(etagFile, etag);

            logger.info("Uploaded object: {}/{} (size: {} bytes, etag: {})", bucketName, objectKey, data.length, etag);
            return etag;
        } catch (IOException e) {
            logger.error("Failed to upload object: {}/{}", bucketName, objectKey, e);
            return null;
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
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> !path.getFileName().toString().endsWith(".etag"))
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
            // Delete ETag sidecar file
            Path etagFile = Paths.get(filePath.toString() + ".etag");
            try { Files.deleteIfExists(etagFile); } catch (IOException ignored) {}

            if (file.delete()) {
                // Clean up empty parent directories up to bucket root
                cleanupEmptyParentDirs(filePath, bucketName);
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
     * Compute MD5 hex string for data
     */
    private String computeMd5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    /**
     * Get ETag for an object
     */
    public String getObjectEtag(String bucketName, String objectKey) {
        Path filePath = getFilePath(bucketName, objectKey);
        Path etagFile = Paths.get(filePath.toString() + ".etag");
        if (Files.exists(etagFile)) {
            try {
                return Files.readString(etagFile).trim();
            } catch (IOException e) {
                logger.warn("Failed to read ETag for {}/{}", bucketName, objectKey, e);
            }
        }
        // Compute on-the-fly as fallback
        File file = filePath.toFile();
        if (file.exists()) {
            try {
                return computeMd5Hex(Files.readAllBytes(file.toPath()));
            } catch (IOException e) {
                logger.warn("Failed to compute ETag for {}/{}", bucketName, objectKey, e);
            }
        }
        return null;
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

    /**
     * Bucket information class
     */
    public static class BucketInfo {
        private final String name;
        private final long creationDateMillis;

        public BucketInfo(String name, long creationDateMillis) {
            this.name = name;
            this.creationDateMillis = creationDateMillis;
        }

        public String getName() {
            return name;
        }

        public long getCreationDateMillis() {
            return creationDateMillis;
        }

        public String getCreationDateFormatted() {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneId.of("UTC"));
            return formatter.format(Instant.ofEpochMilli(creationDateMillis));
        }
    }
}
