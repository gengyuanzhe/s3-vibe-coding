package org.example.auth;

import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AwsV4Signer {

    private static final Logger logger = LoggerFactory.getLogger(AwsV4Signer.class);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private final String region;
    private final String service;
    private final int timeSkewMinutes;

    public AwsV4Signer(String region, String service, int timeSkewMinutes) {
        this.region = region;
        this.service = service;
        this.timeSkewMinutes = timeSkewMinutes;
    }

    public boolean verify(HttpServletRequest request, String secretKey, byte[] bodyBytes) {
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith(ALGORITHM)) {
                return false;
            }

            // Parse Authorization header
            Map<String, String> authParts = parseAuthHeader(authHeader);
            String credentialScope = authParts.get("Credential");
            String signedHeadersStr = authParts.get("SignedHeaders");
            String providedSignature = authParts.get("Signature");

            if (credentialScope == null || signedHeadersStr == null || providedSignature == null) {
                return false;
            }

            // Parse credential: accessKeyId/date/region/service/aws4_request
            String[] credParts = credentialScope.split("/");
            if (credParts.length != 5) return false;

            String dateStamp = credParts[1];
            String credRegion = credParts[2];
            String credService = credParts[3];

            // Validate timestamp
            String amzDate = request.getHeader("x-amz-date");
            if (amzDate == null) amzDate = request.getHeader("X-Amz-Date");
            if (amzDate == null) {
                String dateHeader = request.getHeader("Date");
                if (dateHeader != null) {
                    amzDate = dateHeader;
                } else {
                    return false;
                }
            }

            if (!isTimestampValid(amzDate)) {
                return false;
            }

            // Build canonical request
            List<String> signedHeaders = Arrays.asList(signedHeadersStr.split(";"));
            String canonicalRequest = buildCanonicalRequest(request, signedHeaders, bodyBytes);

            // Build string to sign
            String amzDateFormatted = formatAmzDate(amzDate);
            String scope = dateStamp + "/" + credRegion + "/" + credService + "/aws4_request";
            String stringToSign = ALGORITHM + "\n" +
                    amzDateFormatted + "\n" +
                    scope + "\n" +
                    sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

            // Derive signing key
            byte[] signingKey = deriveSigningKey(secretKey, dateStamp, credRegion, credService);

            // Compute expected signature
            byte[] expectedSig = hmacSha256(signingKey, stringToSign);
            String expectedHex = bytesToHex(expectedSig);

            logger.debug("V4 Signature Verification:");
            logger.debug("  CanonicalRequest:\n{}", canonicalRequest);
            logger.debug("  StringToSign:\n{}", stringToSign);
            logger.debug("  Expected: {}", expectedHex);
            logger.debug("  Provided: {}", providedSignature);

            // Constant-time comparison
            return MessageDigest.isEqual(
                    expectedHex.getBytes(StandardCharsets.UTF_8),
                    providedSignature.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> parseAuthHeader(String authHeader) {
        Map<String, String> parts = new HashMap<>();
        String remainder = authHeader.substring(ALGORITHM.length()).trim();
        String[] segments = remainder.split(",(?=\\s*(Credential|SignedHeaders|Signature)=)");
        for (String segment : segments) {
            segment = segment.trim();
            int eqIdx = segment.indexOf('=');
            if (eqIdx > 0) {
                String key = segment.substring(0, eqIdx).trim();
                String value = segment.substring(eqIdx + 1).trim();
                parts.put(key, value);
            }
        }
        return parts;
    }

    private boolean isTimestampValid(String amzDate) {
        try {
            Instant requestTime = parseAmzDateToInstant(amzDate);
            if (requestTime == null) return false;
            Instant now = Instant.now();
            long skewSeconds = Math.abs(now.getEpochSecond() - requestTime.getEpochSecond());
            return skewSeconds <= timeSkewMinutes * 60L;
        } catch (Exception e) {
            return false;
        }
    }

    private Instant parseAmzDateToInstant(String amzDate) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
            return java.time.LocalDateTime.parse(amzDate, formatter).toInstant(java.time.ZoneOffset.UTC);
        } catch (Exception ignored) {}
        try {
            return Instant.parse(amzDate);
        } catch (Exception ignored) {}
        return null;
    }

    private String formatAmzDate(String amzDate) {
        try {
            Instant instant = parseAmzDateToInstant(amzDate);
            if (instant != null) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
                return formatter.format(instant);
            }
        } catch (Exception ignored) {}
        return amzDate;
    }

    private String buildCanonicalRequest(HttpServletRequest request, List<String> signedHeaders, byte[] bodyBytes) {
        StringBuilder sb = new StringBuilder();

        sb.append(request.getMethod()).append("\n");

        String uri = request.getRequestURI();
        sb.append(uri != null ? uri : "/").append("\n");

        sb.append(buildCanonicalQueryString(request)).append("\n");

        List<String> sortedHeaders = new ArrayList<>(signedHeaders);
        Collections.sort(sortedHeaders, String.CASE_INSENSITIVE_ORDER);
        for (String headerName : sortedHeaders) {
            String value = request.getHeader(headerName);
            if (value == null) value = "";
            value = value.trim().replaceAll("\\s+", " ");
            sb.append(headerName.toLowerCase()).append(":").append(value).append("\n");
        }
        sb.append("\n");

        List<String> sortedLower = new ArrayList<>(signedHeaders);
        sortedLower.sort(String.CASE_INSENSITIVE_ORDER);
        sb.append(String.join(";", sortedLower.stream().map(String::toLowerCase).toList())).append("\n");

        // Payload hash - use x-amz-content-sha256 header if present (e.g. UNSIGNED-PAYLOAD)
        String contentSha256 = request.getHeader("x-amz-content-sha256");
        if (contentSha256 != null && !contentSha256.isEmpty()) {
            sb.append(contentSha256);
        } else {
            sb.append(sha256Hex(bodyBytes != null ? bodyBytes : new byte[0]));
        }

        return sb.toString();
    }

    private String buildCanonicalQueryString(HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isEmpty()) {
            return "";
        }

        String[] params = queryString.split("&");
        Map<String, String> paramMap = new TreeMap<>();
        for (String param : params) {
            int eqIdx = param.indexOf('=');
            if (eqIdx > 0) {
                String key = param.substring(0, eqIdx);
                String value = param.substring(eqIdx + 1);
                paramMap.put(urlEncode(key), urlEncode(value));
            } else {
                paramMap.put(urlEncode(param), "");
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : paramMap.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception e) {
            return value;
        }
    }

    private byte[] deriveSigningKey(String secretKey, String dateStamp, String region, String service) {
        byte[] kDate = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 not available", e);
        }
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return bytesToHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
