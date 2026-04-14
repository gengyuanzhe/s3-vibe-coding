package org.example.unit;

import org.example.auth.AwsV4Signer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AwsV4SignerTest {

    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    private AwsV4Signer signer;

    @BeforeEach
    void setUp() {
        signer = new AwsV4Signer("us-east-1", "s3", 15);
    }

    @Test
    void verify_shouldReturnFalseWhenNoAuthHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);

        assertThat(signer.verify(request, SECRET_KEY, new byte[0])).isFalse();
    }

    @Test
    void verify_shouldReturnFalseWhenWrongAlgorithm() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("AWS some-signature");

        assertThat(signer.verify(request, SECRET_KEY, new byte[0])).isFalse();
    }

    @Test
    void verify_shouldReturnFalseWhenMalformedAuthHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("AWS4-HMAC-SHA256 malformed");

        assertThat(signer.verify(request, SECRET_KEY, new byte[0])).isFalse();
    }

    @Test
    void verify_shouldReturnFalseWhenNoAmzDate() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(
            "AWS4-HMAC-SHA256 Credential=AKID/20260415/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc123");
        when(request.getHeader("x-amz-date")).thenReturn(null);
        when(request.getHeader("X-Amz-Date")).thenReturn(null);
        when(request.getHeader("Date")).thenReturn(null);

        assertThat(signer.verify(request, SECRET_KEY, new byte[0])).isFalse();
    }

    @Test
    void verify_shouldReturnFalseWhenExpiredTimestamp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(
            "AWS4-HMAC-SHA256 Credential=AKID/20200101T000000Z/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc123");
        when(request.getHeader("x-amz-date")).thenReturn("20200101T000000Z");
        when(request.getHeader("X-Amz-Date")).thenReturn("20200101T000000Z");

        assertThat(signer.verify(request, SECRET_KEY, new byte[0])).isFalse();
    }
}
