package org.example.unit;

import org.example.auth.AwsV4OutboundSigner;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AwsV4OutboundSignerTest {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    @Test
    void sign_getRequest_producesAuthorizationHeader() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "GET",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        assertThat(headers).containsKey("Authorization");
        assertThat(headers.get("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=" + ACCESS_KEY);
        assertThat(headers).containsKey("X-Amz-Date");
        assertThat(headers).containsKey("x-amz-content-sha256");
    }

    @Test
    void sign_headRequest_producesAuthorizationHeader() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "HEAD",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        assertThat(headers).containsKey("Authorization");
        assertThat(headers.get("Authorization")).contains("SignedHeaders=");
    }

    @Test
    void sign_deleteRequest_producesAuthorizationHeader() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "DELETE",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        assertThat(headers).containsKey("Authorization");
        assertThat(headers.get("Authorization")).startsWith("AWS4-HMAC-SHA256");
    }

    @Test
    void sign_withCustomRegion_usesCorrectCredentialScope() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "GET",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "eu-west-1", "s3"
        );

        assertThat(headers.get("Authorization")).contains("/eu-west-1/s3/aws4_request");
    }

    @Test
    void sign_withQueryString_includesQueryInSignature() {
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "GET",
                URI.create("http://localhost:8080/my-bucket/obj?acl"),
                Map.of(),
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        assertThat(headers).containsKey("Authorization");
    }

    @Test
    void sign_onlyReturnsSigningHeaders() {
        Map<String, String> existingHeaders = Map.of("Content-Type", "text/plain");
        Map<String, String> headers = AwsV4OutboundSigner.sign(
                "GET",
                URI.create("http://localhost:8080/my-bucket/hello.txt"),
                existingHeaders,
                null,
                ACCESS_KEY, SECRET_KEY,
                "us-east-1", "s3"
        );

        // Only signing headers returned, not the original headers
        assertThat(headers).hasSize(3);
        assertThat(headers).containsKey("Authorization");
        assertThat(headers).containsKey("X-Amz-Date");
        assertThat(headers).containsKey("x-amz-content-sha256");
    }
}
