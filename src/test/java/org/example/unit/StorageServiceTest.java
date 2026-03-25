package org.example.unit;

import org.example.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for StorageService
 */
class StorageServiceTest {

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB

    @TempDir
    Path tempDir;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(tempDir.toString(), MAX_FILE_SIZE);
    }

    @Test
    void createBucket_shouldCreateDirectory() throws Exception {
        // When
        boolean created = storageService.createBucket("test-bucket");

        // Then
        assertThat(created).isTrue();
        File bucketDir = tempDir.resolve("test-bucket").toFile();
        assertThat(bucketDir).exists();
        assertThat(bucketDir).isDirectory();
    }

    @Test
    void createBucket_shouldReturnFalseWhenBucketExists() {
        // Given
        storageService.createBucket("existing-bucket");

        // When
        boolean created = storageService.createBucket("existing-bucket");

        // Then
        assertThat(created).isFalse();
    }

    @Test
    void bucketExists_shouldReturnTrueWhenBucketExists() {
        // Given
        storageService.createBucket("test-bucket");

        // When & Then
        assertThat(storageService.bucketExists("test-bucket")).isTrue();
    }

    @Test
    void bucketExists_shouldReturnFalseWhenBucketNotExists() {
        // When & Then
        assertThat(storageService.bucketExists("non-existent")).isFalse();
    }

    @Test
    void listBuckets_shouldReturnEmptyListWhenNoBuckets() {
        // When & Then
        assertThat(storageService.listBuckets()).isEmpty();
    }

    @Test
    void listBuckets_shouldReturnAllBuckets() {
        // Given
        storageService.createBucket("bucket1");
        storageService.createBucket("bucket2");

        // When & Then
        assertThat(storageService.listBuckets())
                .hasSize(2)
                .contains("bucket1", "bucket2");
    }

    @Test
    void deleteBucket_shouldDeleteEmptyBucket() throws Exception {
        // Given
        storageService.createBucket("to-delete");
        assertThat(storageService.bucketExists("to-delete")).isTrue();

        // When
        boolean deleted = storageService.deleteBucket("to-delete");

        // Then
        assertThat(deleted).isTrue();
        assertThat(storageService.bucketExists("to-delete")).isFalse();
    }

    @Test
    void deleteBucket_shouldReturnFalseWhenBucketNotExists() {
        // When & Then
        assertThat(storageService.deleteBucket("non-existent")).isFalse();
    }

    @Test
    void deleteBucket_shouldReturnFalseWhenBucketNotEmpty() throws Exception {
        // Given
        storageService.createBucket("non-empty");
        Path filePath = tempDir.resolve("non-empty/test.txt");
        Files.writeString(filePath, "test content");

        // When & Then
        assertThat(storageService.deleteBucket("non-empty")).isFalse();
        assertThat(storageService.bucketExists("non-empty")).isTrue();
    }

    @Test
    void putObject_shouldUploadFile() throws Exception {
        // Given
        storageService.createBucket("test-bucket");
        String content = "Hello, World!";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes());

        // When
        boolean uploaded = storageService.putObject("test-bucket", "test.txt", inputStream, content.length());

        // Then
        assertThat(uploaded).isTrue();
        File uploadedFile = tempDir.resolve("test-bucket/test.txt").toFile();
        assertThat(uploadedFile).exists();
        assertThat(uploadedFile).hasContent(content);
    }

    @Test
    void putObject_shouldCreateParentDirectories() throws Exception {
        // Given
        storageService.createBucket("test-bucket");
        String content = "nested file content";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes());

        // When
        boolean uploaded = storageService.putObject("test-bucket", "folder/subfolder/file.txt", inputStream, content.length());

        // Then
        assertThat(uploaded).isTrue();
        assertThat(tempDir.resolve("test-bucket/folder/subfolder/file.txt").toFile()).exists();
    }

    @Test
    void putObject_shouldReturnFalseWhenBucketNotExists() throws Exception {
        // Given
        String content = "test";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes());

        // When & Then
        assertThat(storageService.putObject("non-existent", "file.txt", inputStream, content.length())).isFalse();
    }

    @Test
    void getObject_shouldReturnFileWhenExists() throws Exception {
        // Given
        storageService.createBucket("test-bucket");
        Path filePath = tempDir.resolve("test-bucket/existing.txt");
        Files.writeString(filePath, "existing content");

        // When
        File file = storageService.getObject("test-bucket", "existing.txt");

        // Then
        assertThat(file).isNotNull();
        assertThat(file).exists();
        assertThat(file).hasContent("existing content");
    }

    @Test
    void getObject_shouldReturnNullWhenFileNotExists() {
        // When & Then
        assertThat(storageService.getObject("test-bucket", "non-existent.txt")).isNull();
    }

    @Test
    void getObject_shouldReturnNullWhenBucketNotExists() {
        // When & Then
        assertThat(storageService.getObject("non-existent", "file.txt")).isNull();
    }

    @Test
    void listObjects_shouldReturnEmptyListWhenBucketEmpty() {
        // Given
        storageService.createBucket("test-bucket");

        // When & Then
        assertThat(storageService.listObjects("test-bucket", null)).isEmpty();
    }

    @Test
    void listObjects_shouldReturnAllObjects() throws Exception {
        // Given
        storageService.createBucket("test-bucket");
        Files.writeString(tempDir.resolve("test-bucket/file1.txt"), "content1");
        Files.writeString(tempDir.resolve("test-bucket/file2.txt"), "content2");

        // When
        var objects = storageService.listObjects("test-bucket", null);

        // Then
        assertThat(objects).hasSize(2);
        assertThat(objects)
                .anyMatch(obj -> obj.getKey().equals("file1.txt"))
                .anyMatch(obj -> obj.getKey().equals("file2.txt"));
    }

    @Test
    void listObjects_shouldFilterByPrefix() throws Exception {
        // Given
        storageService.createBucket("test-bucket");
        Files.createDirectories(tempDir.resolve("test-bucket/data"));
        Files.createDirectories(tempDir.resolve("test-bucket/other"));
        Files.writeString(tempDir.resolve("test-bucket/data/file1.txt"), "content1");
        Files.writeString(tempDir.resolve("test-bucket/data/file2.txt"), "content2");
        Files.writeString(tempDir.resolve("test-bucket/other/file3.txt"), "content3");

        // When
        var objects = storageService.listObjects("test-bucket", "data/");

        // Then
        assertThat(objects).hasSize(2);
        assertThat(objects.stream().map(StorageService.ObjectMetadata::getKey))
                .containsExactlyInAnyOrder("data/file1.txt", "data/file2.txt");
    }

    @Test
    void deleteObject_shouldDeleteFile() throws Exception {
        // Given
        storageService.createBucket("test-bucket");
        Path filePath = tempDir.resolve("test-bucket/to-delete.txt");
        Files.writeString(filePath, "content");

        // When
        boolean deleted = storageService.deleteObject("test-bucket", "to-delete.txt");

        // Then
        assertThat(deleted).isTrue();
        assertThat(tempDir.resolve("test-bucket/to-delete.txt")).doesNotExist();
    }

    @Test
    void deleteObject_shouldReturnFalseWhenFileNotExists() {
        // When & Then
        assertThat(storageService.deleteObject("test-bucket", "non-existent.txt")).isFalse();
    }

    @Test
    void deleteObject_shouldReturnFalseWhenBucketNotExists() {
        // When & Then
        assertThat(storageService.deleteObject("non-existent", "file.txt")).isFalse();
    }

    @Test
    void getObjectMetadata_shouldReturnCorrectMetadata() throws Exception {
        // Given
        storageService.createBucket("test-bucket");
        Path filePath = tempDir.resolve("test-bucket/test.jpg");
        Files.writeString(filePath, "image content");

        // When
        var metadata = storageService.getObjectMetadata("test-bucket", "test.jpg");

        // Then
        assertThat(metadata).isNotNull();
        assertThat(metadata.getKey()).isEqualTo("test.jpg");
        assertThat(metadata.getSize()).isGreaterThan(0);
        assertThat(metadata.getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void getObjectMetadata_shouldReturnNullWhenFileNotExists() {
        // When & Then
        assertThat(storageService.getObjectMetadata("test-bucket", "non-existent.txt")).isNull();
    }

    @Test
    void sanitizePathComponent_shouldReplaceSlashes() {
        // Test internal method through createBucket
        // When creating "test/bucket", it should be sanitized to "test_bucket"
        storageService.createBucket("test/bucket");

        // Both sanitized names should point to the same bucket
        assertThat(storageService.bucketExists("test_bucket")).isTrue();
        // "test/bucket" is also sanitized to "test_bucket", so it should also return true
        assertThat(storageService.bucketExists("test/bucket")).isTrue();
    }

    @Test
    void sanitizePathKey_shouldRemovePathTraversal() throws Exception {
        // Given
        storageService.createBucket("test-bucket");
        String content = "test";
        ByteArrayInputStream inputStream = new ByteArrayInputStream(content.getBytes());

        // When - try to upload with path traversal
        boolean uploaded = storageService.putObject("test-bucket", "../escape.txt", inputStream, content.length());

        // Then - should be stored without .. in the path
        assertThat(uploaded).isTrue();
        assertThat(tempDir.resolve("test-bucket/escape.txt").toFile()).exists();
    }
}
