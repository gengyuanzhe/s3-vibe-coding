package org.example.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class AwsCredentialsProvider {
    private static final Logger logger = LoggerFactory.getLogger(AwsCredentialsProvider.class);
    private final Map<String, AwsCredentials> credentialsByAccessKey = new HashMap<>();

    public void load(InputStream inputStream) throws IOException {
        Properties props = new Properties();
        props.load(inputStream);

        Map<String, String> accessKeys = new HashMap<>();
        Map<String, String> secretKeys = new HashMap<>();

        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("accessKey.")) {
                String label = name.substring("accessKey.".length());
                accessKeys.put(label, props.getProperty(name));
            } else if (name.startsWith("secretKey.")) {
                String label = name.substring("secretKey.".length());
                secretKeys.put(label, props.getProperty(name));
            }
        }

        for (Map.Entry<String, String> entry : accessKeys.entrySet()) {
            String label = entry.getKey();
            String accessKeyId = entry.getValue();
            String secretAccessKey = secretKeys.get(label);
            if (secretAccessKey != null) {
                credentialsByAccessKey.put(accessKeyId, new AwsCredentials(accessKeyId, secretAccessKey));
            }
        }

        logger.info("Loaded {} credential pairs", credentialsByAccessKey.size());
    }

    public AwsCredentials getCredentials(String accessKeyId) {
        return credentialsByAccessKey.get(accessKeyId);
    }

    public static AwsCredentialsProvider fromFile(Path path) throws IOException {
        AwsCredentialsProvider provider = new AwsCredentialsProvider();
        try (InputStream is = Files.newInputStream(path)) {
            provider.load(is);
        }
        return provider;
    }
}
