# Origin Proxy V4 Signing Design

## Problem

The origin proxy feature sends unsigned HTTP requests to upstream S3 servers. This means:
- Only works against S3 servers with auth disabled
- Cannot proxy to AWS S3, MinIO (with auth), or any S3-compatible service requiring V4 signatures
- `OriginConfig` already has a `credentials` field but it's never used

## Solution

Use AWS SDK v2's `AwsS3V4Signer` to sign outbound proxy requests when credentials are configured.

## Scope

### Supported Operations
- **GetObject** (GET with objectKey) - existing, add signing
- **HeadObject** (HEAD with objectKey) - existing, add signing
- **DeleteObject** (DELETE with objectKey) - new proxy trigger

### Future extensibility (not implemented now)
- ListObjects, GetObjectAcl, GetObjectTagging - the design supports these through method/queryString parameters

## Changes

### 1. Dependency: promote `s3` to compile scope

`pom.xml`: Change `software.amazon.awssdk:s3` from `<scope>test</scope>` to compile scope (remove scope element). The `s3` module transitively provides `auth` (signers), `sdk-core` (credentials), and `http-client-spi` (request types).

### 2. OriginConfig: add `region` and `service` fields

New fields in `OriginConfig`:
- `region` (String, default `"us-east-1"`) - AWS region for signing key derivation
- `service` (String, default `"s3"`) - service name for signing key derivation

No backward compatibility needed. `toJson()` and `fromJson()` include these fields. `validate()` does not require them (defaults applied when null).

Web UI origin config modal adds two optional text inputs for Region and Service with default-value placeholders.

### 3. New class: `AwsV4OutboundSigner`

Location: `src/main/java/org/example/auth/AwsV4OutboundSigner.java`

Static utility wrapping AWS SDK's `AwsS3V4Signer`:

```java
public static Map<String, String> sign(
    String method, URI url, Map<String, String> headers,
    byte[] body, AwsCredentials credentials,
    String region, String service)
```

Behavior:
- Builds an `SdkHttpFullRequest` from the method, URL, and existing headers
- Configures `Aws4SignerParams` with credentials, region, service
- Calls `AwsS3V4Signer.sign()` to produce the signed request
- Extracts and returns the signing headers: `Authorization`, `X-Amz-Date`, `X-Amz-Content-Sha256`
- For requests without body (GET/HEAD/DELETE), uses `UNSIGNED-PAYLOAD` as the payload hash

### 4. OriginProxyService: integrate signing

In `proxyRequest()`, after building the upstream URL and before sending the request:
- Check `config.hasCredentials()`
- If true, call `AwsV4OutboundSigner.sign()` with the method, URL, credentials, region, service from config
- Apply the returned headers to the `HttpRequest.Builder`

No method signature changes needed - `proxyRequest(bucketName, objectKey, method, queryString)` already accepts arbitrary methods.

### 5. S3Servlet: add DELETE proxy trigger

`handleDeleteObject()` changes:
1. Check bucket exists (unchanged)
2. Try local delete (unchanged)
3. If local file doesn't exist, call `tryOriginProxy(resp, bucketName, objectKey, "DELETE", req.getQueryString())`
4. If proxy succeeds, return the proxy result (204 on success)
5. If no origin configured, return 404 NoSuchKey (unchanged)

### 6. Web UI: add Region and Service fields

In `index.html` origin config modal:
- Add Region text input (optional, placeholder: `us-east-1`)
- Add Service text input (optional, placeholder: `s3`)
- `saveOriginConfig()` includes `region` and `service` in the JSON payload
- `openOriginConfigModal()` populates these fields from existing config

## Error Handling

- If signing fails (e.g., invalid credentials, SDK error), log a warning and return 502 `OriginError` to the client
- Signing failure does not fall through to local handling - the proxy was configured but couldn't authenticate to upstream

## Testing

### Unit Tests
- `AwsV4OutboundSignerTest`: Verify signing produces correct Authorization header format for GET/HEAD/DELETE
- `OriginProxyServiceTest`: Add tests for signed proxy requests (mock the signer or use a real upstream with V4 auth)
- `OriginConfigTest`: Add tests for region/service fields

### Integration Tests
- `OriginProxyIntegrationTest`: Add test where the upstream S3 instance has `aws-v4` auth mode enabled, verifying that proxy with credentials succeeds and proxy without credentials gets 403
