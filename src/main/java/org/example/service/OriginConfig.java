package org.example.service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OriginConfig {

    public record Credentials(String accessKey, String secretKey) {}

    private static final Set<String> VALID_POLICIES = Set.of("no-cache", "cache", "cache-ttl");

    private static final Pattern ORIGIN_URL_PATTERN = Pattern.compile("\"originUrl\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ORIGIN_BUCKET_PATTERN = Pattern.compile("\"originBucket\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PREFIX_PATTERN = Pattern.compile("\"prefix\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CACHE_POLICY_PATTERN = Pattern.compile("\"cachePolicy\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CREDENTIALS_BLOCK_PATTERN = Pattern.compile("\"credentials\"\\s*:\\s*\\{([^}]*)}");
    private static final Pattern ACCESS_KEY_PATTERN = Pattern.compile("\"accessKey\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SECRET_KEY_PATTERN = Pattern.compile("\"secretKey\"\\s*:\\s*\"([^\"]+)\"");

    private final String originUrl;
    private final String originBucket;
    private final String prefix;
    private final String cachePolicy;
    private final Credentials credentials;

    public OriginConfig(String originUrl, String originBucket, String prefix,
                        String cachePolicy, Credentials credentials) {
        this.originUrl = originUrl;
        this.originBucket = originBucket;
        this.prefix = prefix;
        this.cachePolicy = cachePolicy;
        this.credentials = credentials;
    }

    public String getOriginUrl() {
        return originUrl;
    }

    public String getOriginBucket() {
        return originBucket;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getCachePolicy() {
        return cachePolicy;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public boolean matches(String objectKey) {
        if (prefix == null || prefix.isEmpty()) {
            return true;
        }
        return objectKey != null && objectKey.startsWith(prefix);
    }

    public boolean hasCredentials() {
        return credentials != null
                && credentials.accessKey() != null && !credentials.accessKey().isEmpty()
                && credentials.secretKey() != null && !credentials.secretKey().isEmpty();
    }

    public boolean validate() {
        if (originUrl == null || originUrl.isBlank()) return false;
        if (originBucket == null || originBucket.isBlank()) return false;
        if (cachePolicy == null || !VALID_POLICIES.contains(cachePolicy)) return false;
        if (credentials != null && !hasCredentials()) return false;
        return true;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"originUrl\":\"").append(escapeJson(originUrl)).append("\",");
        sb.append("\"originBucket\":\"").append(escapeJson(originBucket)).append("\",");
        if (prefix != null) {
            sb.append("\"prefix\":\"").append(escapeJson(prefix)).append("\",");
        }
        sb.append("\"cachePolicy\":\"").append(escapeJson(cachePolicy)).append("\"");
        if (credentials != null) {
            sb.append(",\"credentials\":{\"accessKey\":\"").append(escapeJson(credentials.accessKey()))
              .append("\",\"secretKey\":\"").append(escapeJson(credentials.secretKey())).append("\"}");
        }
        sb.append("}");
        return sb.toString();
    }

    public static OriginConfig fromJson(String json) {
        if (json == null || json.isEmpty()) return null;

        String originUrl = extract(json, ORIGIN_URL_PATTERN);
        String originBucket = extract(json, ORIGIN_BUCKET_PATTERN);
        String prefix = extract(json, PREFIX_PATTERN);
        String cachePolicy = extract(json, CACHE_POLICY_PATTERN);

        Credentials credentials = null;
        Matcher credsMatcher = CREDENTIALS_BLOCK_PATTERN.matcher(json);
        if (credsMatcher.find()) {
            String credsBlock = credsMatcher.group(1);
            String accessKey = extract(credsBlock, ACCESS_KEY_PATTERN);
            String secretKey = extract(credsBlock, SECRET_KEY_PATTERN);
            if (accessKey != null && secretKey != null) {
                credentials = new Credentials(accessKey, secretKey);
            }
        }

        return new OriginConfig(originUrl, originBucket, prefix, cachePolicy, credentials);
    }

    private static String extract(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
