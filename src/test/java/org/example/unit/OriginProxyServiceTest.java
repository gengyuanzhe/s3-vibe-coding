package org.example.unit;

import org.example.service.OriginConfig;
import org.example.service.OriginProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                creds
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
                null
        );
        OriginConfig second = new OriginConfig(
                "https://second.example.com",
                "second-bucket",
                "docs/",
                "cache-ttl",
                new OriginConfig.Credentials("newKey", "newSecret")
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
                "https://origin.example.com", "origin-bucket", null, "cache", null
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
                new OriginConfig.Credentials("ak", "sk")
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
}
