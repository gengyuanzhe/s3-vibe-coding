# Origin Proxy (回源代理) Design

## Overview

Add origin proxy functionality to the S3 storage service. When a bucket is configured with an origin policy, requests for objects that don't exist locally are forwarded to a configured upstream storage endpoint. The upstream response is returned to the client as-is.

## Trigger Conditions

- The local object file does NOT exist
- The bucket has an origin proxy configuration
- The object key matches the configured prefix (or prefix is not set)

This applies uniformly to all supported operations — the check is always "does the local object file exist?", regardless of sub-resource type (acl, tagging).

## Supported Operations

| Operation | Method | Path | Notes |
|-----------|--------|------|-------|
| GetObject | GET | `/{bucket}/{key}` | Full proxy (headers + body) |
| HeadObject | HEAD | `/{bucket}/{key}` | Headers only |
| GetObjectAcl | GET | `/{bucket}/{key}?acl` | Proxy with `?acl` query |
| GetObjectTagging | GET | `/{bucket}/{key}?tagging` | Proxy with `?tagging` query |

## Configuration Model

Each bucket's origin config is stored in `{storageRoot}/{bucket}/.origin-config.json`.

```json
{
  "originUrl": "https://source-bucket.s3.amazonaws.com",
  "originBucket": "source-bucket",
  "prefix": "media/",
  "cachePolicy": "no-cache",
  "credentials": {
    "accessKey": "AKID...",
    "secretKey": "secret..."
  }
}
```

### Fields

| Field | Required | Description |
|-------|----------|-------------|
| `originUrl` | Yes | Upstream storage endpoint URL |
| `originBucket` | Yes | Source bucket name |
| `prefix` | No | Only proxy objects whose key starts with this prefix. Omit or null to match all keys |
| `cachePolicy` | Yes | Currently only `no-cache` is implemented. `cache` and `cache-ttl` are reserved for future use |
| `credentials` | No | If provided, requests are signed with AWS V4. If omitted, anonymous access to origin |
| `credentials.accessKey` | Conditional | Source storage access key (required if `credentials` is present) |
| `credentials.secretKey` | Conditional | Source storage secret key (required if `credentials` is present) |

## Request Flow

```
Client request (GET/HEAD /{bucket}/{key}[?acl|?tagging])
  → S3Servlet handler
    1. Check if local object file exists
       → Exists: return local object (normal behavior)
       → Not found:
         2. Load origin config for bucket
            2a. No config → 404 NoSuchKey
            2b. Has config → check prefix match
                2b-i. No match → 404 NoSuchKey
                2b-ii. Match → OriginProxyService.proxyRequest()
                    - Build HTTP request to {originUrl}/{originBucket}/{key}[?query]
                    - If credentials configured, sign with AWS V4
                    - Forward upstream response to client
                    - On upstream failure → 502 Bad Gateway + S3-style XML error
```

## Architecture

### New Classes

**`OriginProxyService`** (`service/OriginProxyService.java`)
- Manages origin config (CRUD on `.origin-config.json`)
- Executes proxy requests via Java `HttpClient`
- Reuses `AwsV4Signer` for signing requests when credentials are provided
- Thread-safe, uses the storage root dir from `StorageService`

**`OriginConfig`** (`service/OriginConfig.java`)
- POJO mapping the JSON config
- `boolean matches(String objectKey)` — prefix matching
- `boolean hasCredentials()` — check if AK/SK configured

**`ProxyResult`** (inner class of `OriginProxyService`)
- `int statusCode`, `Map<String, String> headers`, `byte[] body`
- For HEAD requests, body is null

### Modified Classes

**`S3Servlet.java`**
- Inject `OriginProxyService` (initialized in `init()`)
- `handleGetObject()`: after local miss, call origin proxy before returning 404
- Add explicit `doHead()` override with `handleHeadObject()` method
- `handleGetObjectAcl()` / `handleGetObjectTagging()`: parse query string, check local object existence (not ACL/tagging existence), proxy if applicable

**`AuthAdminServlet.java`** (or new `OriginConfigServlet.java`)
- Add endpoints under `/admin/origin-config/`
- See Admin API section below

**`index.html`**
- Add "Origin Config" entry in bucket operations menu
- Config form: origin URL, origin bucket, prefix (optional), cache policy, credentials (optional)
- Save/Delete buttons calling Admin API

### Admin API

| Method | Path | Body | Description |
|--------|------|------|-------------|
| GET | `/admin/origin-config/{bucket}` | — | View origin config (404 if not configured) |
| PUT | `/admin/origin-config/{bucket}` | JSON config | Create or update config |
| DELETE | `/admin/origin-config/{bucket}` | — | Delete config (204) |

Auth bypass: origin-config endpoints follow the same auth mode as existing `/admin/*` paths.

## Error Handling

| Scenario | HTTP Status | S3 Error Code |
|----------|-------------|---------------|
| Origin config missing | 404 | `NoSuchBucketPolicy` |
| Upstream timeout | 502 | `OriginTimeout` |
| Upstream error (4xx/5xx) | 502 | `OriginError` |
| Upstream returns 404 | 404 | `NoSuchKey` |
| Upstream returns other status | Passthrough | Passthrough |

Upstream 404 responses are passed through as 404 NoSuchKey. All other upstream errors are wrapped as 502.

## Testing

### Unit Tests
- `OriginConfigTest`: JSON parsing, prefix matching, credential checks
- `OriginProxyServiceTest`: config CRUD, proxy request building (mock HttpClient)

### Integration Tests
- `OriginProxyIntegrationTest`: Start Jetty server, configure origin, verify proxy behavior
  - Test with mock upstream (simple HTTP server)
  - GetObject proxy, HeadObject proxy, ACL/Tagging proxy
  - Prefix filtering
  - Authenticated vs anonymous upstream
  - Upstream failure scenarios
