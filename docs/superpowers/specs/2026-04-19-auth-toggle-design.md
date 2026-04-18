# Auth Toggle from Web UI - Design Spec

## Goal

Allow runtime toggling of V4 authentication mode from the web management interface, without restarting the service.

## Background

Currently, auth is configured statically in `web.xml` via two init-params (`auth.enabled` and `auth.mode`). Changing auth requires editing `web.xml` and restarting the server. The web UI has no admin functionality and cannot operate when auth is in strict `aws-v4` mode (it sends unsigned requests).

## Design Decisions

### Merge `auth.enabled` + `auth.mode` into single `auth.mode`

The `auth.enabled=false` state is functionally identical to `auth.mode=none`. Merging into one parameter simplifies the mental model, the API, and the UI.

**Three modes:**

| Value | Behavior |
|-------|----------|
| `aws-v4` | Strict. Every request must carry a valid V4 signature. |
| `both` | Permissive. If a request has a V4 signature, verify it; if not, allow through. |
| `none` | Disabled. All requests pass through. |

### Runtime state via AuthState singleton

A new `AuthState` singleton holds the current `authMode` as a `volatile` field. Both the Filter and the AdminServlet read from and write to this single source of truth. Initialized from `web.xml` init-param on startup; mutable at runtime via the admin API.

### Independent AuthAdminServlet

A dedicated servlet mapped to `/admin/*`, separate from `S3Servlet`. Keeps admin concerns out of the S3 request-handling path.

### No auth on admin endpoints

The `/admin/*` paths are bypassed by the auth filter, making the settings page always accessible. This is acceptable for a development/testing-oriented tool.

### UI: settings card in existing page

A fourth card added to the existing grid in `index.html`, using the same white-card style. A single dropdown for mode selection and an "apply" button.

## API

### `GET /admin/auth-status`

Returns current auth mode.

**Response (200):**
```json
{ "mode": "aws-v4" }
```

### `POST /admin/auth-status`

Updates auth mode.

**Request body:**
```json
{ "mode": "both" }
```

**Response (200):**
```json
{ "mode": "both" }
```

**Validation:** `mode` must be one of `aws-v4`, `both`, `none`. Returns 400 for invalid values.

## Architecture

```
index.html (Web UI)
    |
    +-- existing fetch calls --> S3Servlet (/*) --> Filter reads AuthState --> StorageService
    |
    +-- settings card
         |
         +-- GET/POST /admin/auth-status --> AuthAdminServlet (/admin/*)
                                                  |
                                                  +-- reads/writes AuthState (singleton)
                                                         ^
                                         AwsV4AuthenticationFilter reads on every request
```

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `src/main/java/org/example/auth/AuthState.java` | New | Singleton holding volatile `authMode`. Initialized from web.xml default. |
| `src/main/java/org/example/servlet/AuthAdminServlet.java` | New | Handles GET/POST `/admin/auth-status`. Reads/writes AuthState. |
| `src/main/java/org/example/filter/AwsV4AuthenticationFilter.java` | Modify | Remove `authEnabled` field. Read `authMode` from `AuthState` instead of init-param. Add `/admin/*` bypass. Simplify doFilter to switch on mode string. |
| `src/main/webapp/WEB-INF/web.xml` | Modify | Remove `auth.enabled` param. Keep `auth.mode` param. Add `AuthAdminServlet` declaration and `/admin/*` mapping. |
| `src/main/webapp/index.html` | Modify | Add fourth card with mode dropdown and apply button. JS: load status on page load, POST on apply. |
| Unit tests | Modify | Adapt to single `authMode` parameter. |
| Integration tests | Modify | Adapt to single `authMode` parameter. |

## AuthState

```java
public class AuthState {
    private static final AuthState INSTANCE = new AuthState();
    private volatile String authMode;

    public static AuthState getInstance() { return INSTANCE; }

    public String getAuthMode() { return authMode; }
    public void setAuthMode(String mode) { this.authMode = mode; }

    public void init(String defaultMode) { this.authMode = defaultMode; }
}
```

## AwsV4AuthenticationFilter Changes

**Removed:** `authEnabled` boolean field. `auth.enabled` init-param handling.

**Changed `doFilter` logic:**
```
path = /health or /admin/*  →  pass through
mode = "none"               →  pass through
mode = "both"               →  if signature present, verify; else pass through
mode = "aws-v4"             →  must have valid signature
```

**Init:** Read `auth.mode` init-param, call `AuthState.getInstance().init(mode)`.

## AuthAdminServlet

Mapped to `/admin/*` in web.xml.

- `doGet`: Return `AuthState.getInstance().getAuthMode()` as JSON.
- `doPost`: Parse `{"mode": "..."}` from request body, validate, call `AuthState.getInstance().setAuthMode(mode)`, return updated state as JSON.
- Input validation: reject unknown mode values with 400.

## Web UI Settings Card

Added as the fourth card in the existing CSS grid. Contains:

- A label "认证模式"
- A `<select>` dropdown with three options: `aws-v4 (严格)`, `both (宽松)`, `none (关闭)`
- An "应用设置" button
- A status message area for feedback

On page load: `GET /admin/auth-status`, populate dropdown.
On button click: `POST /admin/auth-status` with selected mode, show feedback.

## web.xml Changes

Removed:
```xml
<param-name>auth.enabled</param-name>
<param-value>true</param-value>
```

Kept (as default):
```xml
<param-name>auth.mode</param-name>
<param-value>aws-v4</param-value>
```

Added:
```xml
<servlet>
    <servlet-name>AuthAdminServlet</servlet-name>
    <servlet-class>org.example.servlet.AuthAdminServlet</servlet-class>
</servlet>
<servlet-mapping>
    <servlet-name>AuthAdminServlet</servlet-name>
    <url-pattern>/admin/*</url-pattern>
</servlet-mapping>
```

## Testing

- **Unit tests:** Update `AwsV4SignerTest` and `S3ServletTest` if they reference `auth.enabled`. Add `AuthStateTest` to verify singleton behavior and volatile semantics.
- **Integration tests:** Update `S3StorageIntegrationTest` and `AwsSdkIntegrationTest` override-web.xml to remove `auth.enabled`, keep only `auth.mode`. Add test cases for the `/admin/auth-status` GET/POST endpoints.
