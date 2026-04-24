package org.example.unit;

import org.example.service.OriginConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OriginConfigTest {

    @Test
    void matches_returnsTrue_whenKeyStartsWithPrefix() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", "images/",
                "no-cache", null, null, null);

        assertThat(config.matches("images/photo.jpg")).isTrue();
        assertThat(config.matches("images/sub/dir/file.png")).isTrue();
    }

    @Test
    void matches_returnsFalse_whenKeyDoesNotStartWithPrefix() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", "images/",
                "no-cache", null, null, null);

        assertThat(config.matches("documents/report.pdf")).isFalse();
        assertThat(config.matches("videos/clip.mp4")).isFalse();
    }

    @Test
    void matches_returnsTrue_forAllKeys_whenPrefixIsNull() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", null, null, null);

        assertThat(config.matches("anything")).isTrue();
        assertThat(config.matches("deep/nested/key.txt")).isTrue();
    }

    @Test
    void matches_returnsTrue_forAllKeys_whenPrefixIsEmpty() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", "",
                "no-cache", null, null, null);

        assertThat(config.matches("anything")).isTrue();
        assertThat(config.matches("deep/nested/key.txt")).isTrue();
    }

    @Test
    void hasCredentials_returnsFalse_whenNull() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", null, null, null);

        assertThat(config.hasCredentials()).isFalse();
    }

    @Test
    void hasCredentials_returnsTrue_whenPresent() {
        OriginConfig.Credentials creds = new OriginConfig.Credentials("AKID", "SECRET");
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", creds, null, null);

        assertThat(config.hasCredentials()).isTrue();
    }

    @Test
    void getters_returnAllFields() {
        OriginConfig.Credentials creds = new OriginConfig.Credentials("AKID", "SECRET");
        OriginConfig config = new OriginConfig(
                "http://origin.example.com", "my-bucket", "prefix/",
                "no-cache", creds, "eu-west-1", "s3");

        assertThat(config.getOriginUrl()).isEqualTo("http://origin.example.com");
        assertThat(config.getOriginBucket()).isEqualTo("my-bucket");
        assertThat(config.getPrefix()).isEqualTo("prefix/");
        assertThat(config.getCachePolicy()).isEqualTo("no-cache");
        assertThat(config.getCredentials()).isEqualTo(creds);
        assertThat(config.getCredentials().accessKey()).isEqualTo("AKID");
        assertThat(config.getCredentials().secretKey()).isEqualTo("SECRET");
        assertThat(config.getRegion()).isEqualTo("eu-west-1");
        assertThat(config.getService()).isEqualTo("s3");
    }

    @Test
    void fromJson_parsesValidJsonWithAllFields() {
        String json = "{\"originUrl\":\"http://localhost:9000\",\"originBucket\":\"src\",\"prefix\":\"docs/\",\"cachePolicy\":\"no-cache\",\"credentials\":{\"accessKey\":\"AKID\",\"secretKey\":\"SK\"}}";

        OriginConfig config = OriginConfig.fromJson(json);

        assertThat(config).isNotNull();
        assertThat(config.getOriginUrl()).isEqualTo("http://localhost:9000");
        assertThat(config.getOriginBucket()).isEqualTo("src");
        assertThat(config.getPrefix()).isEqualTo("docs/");
        assertThat(config.getCachePolicy()).isEqualTo("no-cache");
        assertThat(config.hasCredentials()).isTrue();
        assertThat(config.getCredentials().accessKey()).isEqualTo("AKID");
        assertThat(config.getCredentials().secretKey()).isEqualTo("SK");
    }

    @Test
    void fromJson_handlesMissingOptionalFields() {
        String json = "{\"originUrl\":\"http://localhost:9000\",\"originBucket\":\"src\",\"cachePolicy\":\"no-cache\"}";

        OriginConfig config = OriginConfig.fromJson(json);

        assertThat(config).isNotNull();
        assertThat(config.getOriginUrl()).isEqualTo("http://localhost:9000");
        assertThat(config.getOriginBucket()).isEqualTo("src");
        assertThat(config.getPrefix()).isNull();
        assertThat(config.getCachePolicy()).isEqualTo("no-cache");
        assertThat(config.getCredentials()).isNull();
        assertThat(config.hasCredentials()).isFalse();
    }

    @Test
    void toJson_serializesAllFieldsIncludingCredentials() {
        OriginConfig.Credentials creds = new OriginConfig.Credentials("AKID", "SECRET");
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src", "docs/",
                "no-cache", creds, null, null);

        String json = config.toJson();

        assertThat(json).contains("\"originUrl\":\"http://localhost:9000\"");
        assertThat(json).contains("\"originBucket\":\"src\"");
        assertThat(json).contains("\"prefix\":\"docs/\"");
        assertThat(json).contains("\"cachePolicy\":\"no-cache\"");
        assertThat(json).contains("\"credentials\"");
        assertThat(json).contains("\"accessKey\":\"AKID\"");
        assertThat(json).contains("\"secretKey\":\"SECRET\"");
    }

    @Test
    void toJson_omitsNullPrefixAndNullCredentials() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src", null,
                "no-cache", null, null, null);

        String json = config.toJson();

        assertThat(json).contains("\"originUrl\":\"http://localhost:9000\"");
        assertThat(json).contains("\"originBucket\":\"src\"");
        assertThat(json).contains("\"cachePolicy\":\"no-cache\"");
        assertThat(json).doesNotContain("\"prefix\"");
        assertThat(json).doesNotContain("\"credentials\"");
    }

    @Test
    void validate_rejectsMissingOriginUrl() {
        OriginConfig config = new OriginConfig(
                "", "src-bucket", null,
                "no-cache", null, null, null);

        assertThat(config.validate()).isFalse();
    }

    @Test
    void validate_rejectsMissingOriginBucket() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "", null,
                "no-cache", null, null, null);

        assertThat(config.validate()).isFalse();
    }

    @Test
    void validate_rejectsInvalidCachePolicy() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "invalid-policy", null, null, null);

        assertThat(config.validate()).isFalse();
    }

    @Test
    void validate_acceptsValidConfig() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", null, null, null);

        assertThat(config.validate()).isTrue();
    }

    @Test
    void validate_shouldRejectIncompleteCredentials() {
        var config = new OriginConfig("https://example.com", "src", null, "no-cache",
                new OriginConfig.Credentials("AK", ""), null, null);
        assertThat(config.validate()).isFalse();
    }

    @Test
    void hasCredentials_shouldReturnFalseWhenAccessKeyEmpty() {
        var creds = new OriginConfig.Credentials("", "secret");
        var config = new OriginConfig("https://example.com", "src", null, "no-cache", creds, null, null);
        assertThat(config.hasCredentials()).isFalse();
    }

    @Test
    void getters_returnRegionAndService() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", null, "eu-west-1", "custom-service");

        assertThat(config.getRegion()).isEqualTo("eu-west-1");
        assertThat(config.getService()).isEqualTo("custom-service");
    }

    @Test
    void region_defaultsToUsEast1() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", null, null, null);

        assertThat(config.getRegion()).isEqualTo("us-east-1");
        assertThat(config.getService()).isEqualTo("s3");
    }

    @Test
    void region_defaultsToUsEast1_whenEmpty() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src-bucket", null,
                "no-cache", null, "", "");

        assertThat(config.getRegion()).isEqualTo("us-east-1");
        assertThat(config.getService()).isEqualTo("s3");
    }

    @Test
    void toJson_includesRegionAndService() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src", null,
                "no-cache", null, "eu-west-1", "s3");

        String json = config.toJson();

        assertThat(json).contains("\"region\":\"eu-west-1\"");
        assertThat(json).contains("\"service\":\"s3\"");
    }

    @Test
    void toJson_includesDefaultRegionAndService() {
        OriginConfig config = new OriginConfig(
                "http://localhost:9000", "src", null,
                "no-cache", null, null, null);

        String json = config.toJson();

        assertThat(json).contains("\"region\":\"us-east-1\"");
        assertThat(json).contains("\"service\":\"s3\"");
    }

    @Test
    void fromJson_parsesRegionAndService() {
        String json = "{\"originUrl\":\"http://localhost:9000\",\"originBucket\":\"src\",\"cachePolicy\":\"no-cache\",\"region\":\"ap-southeast-1\",\"service\":\"s3\"}";

        OriginConfig config = OriginConfig.fromJson(json);

        assertThat(config).isNotNull();
        assertThat(config.getRegion()).isEqualTo("ap-southeast-1");
        assertThat(config.getService()).isEqualTo("s3");
    }

    @Test
    void fromJson_defaultsRegionAndServiceWhenMissing() {
        String json = "{\"originUrl\":\"http://localhost:9000\",\"originBucket\":\"src\",\"cachePolicy\":\"no-cache\"}";

        OriginConfig config = OriginConfig.fromJson(json);

        assertThat(config).isNotNull();
        assertThat(config.getRegion()).isEqualTo("us-east-1");
        assertThat(config.getService()).isEqualTo("s3");
    }
}
