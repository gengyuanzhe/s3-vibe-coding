package org.example.auth;

public class AwsCredentials {
    private final String accessKeyId;
    private final String secretAccessKey;

    public AwsCredentials(String accessKeyId, String secretAccessKey) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
    }

    public String getAccessKeyId() {
        return accessKeyId;
    }

    public String getSecretAccessKey() {
        return secretAccessKey;
    }
}
