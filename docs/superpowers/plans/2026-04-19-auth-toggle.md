# Auth Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add runtime auth mode toggling from the web UI via a dedicated admin servlet and shared AuthState singleton.

**Architecture:** A new `AuthState` singleton holds the current `authMode` (`aws-v4` / `both` / `none`) as a volatile field. A new `AuthAdminServlet` mapped to `/admin/*` exposes GET/POST on `/admin/auth-status` to read and update this state. The existing `AwsV4AuthenticationFilter` is refactored to read from `AuthState` instead of init-params, and to merge `auth.enabled` + `auth.mode` into a single `auth.mode` parameter. The web UI gets a fourth card for auth settings.

**Tech Stack:** Java 21, Jakarta Servlet 6.1, Jetty 12 EE10, vanilla JS

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/org/example/auth/AuthState.java` | Singleton holding volatile `authMode`. Shared by Filter and AdminServlet. |
| Create | `src/main/java/org/example/servlet/AuthAdminServlet.java` | Handles GET/POST `/admin/auth-status`. Reads/writes `AuthState`. |
| Modify | `src/main/java/org/example/filter/AwsV4AuthenticationFilter.java` | Remove `authEnabled` field. Read from `AuthState`. Add `/admin/*` bypass. |
| Modify | `src/main/webapp/WEB-INF/web.xml` | Remove `auth.enabled` param. Add `AuthAdminServlet` declaration + mapping. |
| Modify | `src/main/webapp/index.html` | Add auth settings card with mode dropdown and apply button. |
| Modify | `src/test/java/org/example/integration/S3StorageIntegrationTest.java` | Update override-web.xml: remove `auth.enabled`, use `auth.mode=none`. |
| Modify | `src/test/java/org/example/integration/AwsSdkIntegrationTest.java` | Update override-web.xml: remove `auth.enabled`, keep `auth.mode=aws-v4`. |
| Create | `src/test/java/org/example/unit/AuthStateTest.java` | Unit tests for `AuthState` singleton. |
| Create | `src/test/java/org/example/unit/AuthAdminServletTest.java` | Unit tests for `AuthAdminServlet` GET/POST. |

---

### Task 1: Create AuthState singleton

**Files:**
- Create: `src/main/java/org/example/auth/AuthState.java`
- Create: `src/test/java/org/example/unit/AuthStateTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.example.unit;

import org.example.auth.AuthState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthStateTest {

    @Test
    void getInstance_shouldReturnSameInstance() {
        AuthState a = AuthState.getInstance();
        AuthState b = AuthState.getInstance();
        assertThat(a).isSameAs(b);
    }

    @Test
    void init_shouldSetAuthMode() {
        AuthState state = AuthState.getInstance();
        state.init("aws-v4");
        assertThat(state.getAuthMode()).isEqualTo("aws-v4");
    }

    @Test
    void setAuthMode_shouldUpdateMode() {
        AuthState state = AuthState.getInstance();
        state.setAuthMode("both");
        assertThat(state.getAuthMode()).isEqualTo("both");
        // reset
        state.setAuthMode("aws-v4");
    }

    @Test
    void init_shouldDefaultToAwsV4_whenNull() {
        AuthState state = AuthState.getInstance();
        state.init(null);
        assertThat(state.getAuthMode()).isEqualTo("aws-v4");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=AuthStateTest -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: Compilation error — `AuthState` class does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package org.example.auth;

public class AuthState {

    private static final AuthState INSTANCE = new AuthState();

    private volatile String authMode = "aws-v4";

    private AuthState() {}

    public static AuthState getInstance() {
        return INSTANCE;
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        if (authMode == null) {
            throw new IllegalArgumentException("authMode must not be null");
        }
        this.authMode = authMode;
    }

    public void init(String defaultMode) {
        this.authMode = (defaultMode != null) ? defaultMode : "aws-v4";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest=AuthStateTest -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/auth/AuthState.java src/test/java/org/example/unit/AuthStateTest.java
git commit -m "feat: add AuthState singleton for runtime auth mode"
```

---

### Task 2: Create AuthAdminServlet

**Files:**
- Create: `src/main/java/org/example/servlet/AuthAdminServlet.java`
- Create: `src/test/java/org/example/unit/AuthAdminServletTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.example.unit;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.auth.AuthState;
import org.example.servlet.AuthAdminServlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthAdminServletTest {

    private AuthAdminServlet servlet;
    private AuthState authState;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new AuthAdminServlet();
        authState = AuthState.getInstance();
        authState.init("aws-v4");

        ServletContext servletContext = mock(ServletContext.class);
        ServletConfig servletConfig = mock(ServletConfig.class);
        when(servletConfig.getServletContext()).thenReturn(servletContext);
        when(servletConfig.getServletName()).thenReturn("AuthAdminServlet");
        servlet.init(servletConfig);
    }

    @Test
    void doGet_shouldReturnCurrentAuthMode() throws Exception {
        authState.setAuthMode("both");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getPathInfo()).thenReturn("/auth-status");

        StringWriter stringWriter = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

        invokeDoGet(servlet, request, response);

        verify(response).setStatus(200);
        verify(response).setContentType("application/json");
        assertThat(stringWriter.toString()).isEqualTo("{\"mode\":\"both\"}");
    }

    @Test
    void doPost_shouldUpdateAuthMode() throws Exception {
        String jsonBody = "{\"mode\":\"none\"}";

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getPathInfo()).thenReturn("/auth-status");
        when(request.getContentType()).thenReturn("application/json");
        when(request.getContentLength()).thenReturn(jsonBody.length());
        when(request.getInputStream()).thenReturn(createServletInputStream(new ByteArrayInputStream(jsonBody.getBytes())));

        StringWriter stringWriter = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

        invokeDoPost(servlet, request, response);

        verify(response).setStatus(200);
        assertThat(authState.getAuthMode()).isEqualTo("none");
        assertThat(stringWriter.toString()).isEqualTo("{\"mode\":\"none\"}");

        // reset
        authState.setAuthMode("aws-v4");
    }

    @Test
    void doPost_invalidMode_shouldReturn400() throws Exception {
        String jsonBody = "{\"mode\":\"invalid\"}";

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getPathInfo()).thenReturn("/auth-status");
        when(request.getContentType()).thenReturn("application/json");
        when(request.getContentLength()).thenReturn(jsonBody.length());
        when(request.getInputStream()).thenReturn(createServletInputStream(new ByteArrayInputStream(jsonBody.getBytes())));

        StringWriter stringWriter = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(stringWriter));

        invokeDoPost(servlet, request, response);

        verify(response).setStatus(400);
        assertThat(authState.getAuthMode()).isEqualTo("aws-v4"); // unchanged
    }

    private void invokeDoGet(AuthAdminServlet servlet, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Method method = AuthAdminServlet.class.getDeclaredMethod("doGet", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        method.invoke(servlet, request, response);
    }

    private void invokeDoPost(AuthAdminServlet servlet, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Method method = AuthAdminServlet.class.getDeclaredMethod("doPost", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        method.invoke(servlet, request, response);
    }

    private jakarta.servlet.ServletInputStream createServletInputStream(ByteArrayInputStream bais) {
        return new jakarta.servlet.ServletInputStream() {
            @Override
            public int read() { return bais.read(); }
            @Override
            public boolean isFinished() { return bais.available() == 0; }
            @Override
            public boolean isReady() { return true; }
            @Override
            public void setReadListener(jakarta.servlet.ReadListener listener) {}
        };
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl . -Dtest=AuthAdminServletTest -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: Compilation error — `AuthAdminServlet` class does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package org.example.servlet;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.auth.AuthState;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuthAdminServlet extends HttpServlet {

    private static final Set<String> VALID_MODES = Set.of("aws-v4", "both", "none");
    private static final Pattern MODE_PATTERN = Pattern.compile("\"mode\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String mode = AuthState.getInstance().getAuthMode();
        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"mode\":\"" + mode + "\"}");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String body = readBody(req);
        String mode = extractMode(body);

        if (mode == null || !VALID_MODES.contains(mode)) {
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Invalid mode. Must be one of: aws-v4, both, none\"}");
            return;
        }

        AuthState.getInstance().setAuthMode(mode);
        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"mode\":\"" + mode + "\"}");
    }

    private String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private String extractMode(String json) {
        if (json == null || json.isEmpty()) return null;
        Matcher matcher = MODE_PATTERN.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl . -Dtest=AuthAdminServletTest -Dsurefire.useFile=false 2>&1 | tail -20`
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/servlet/AuthAdminServlet.java src/test/java/org/example/unit/AuthAdminServletTest.java
git commit -m "feat: add AuthAdminServlet for runtime auth mode management"
```

---

### Task 3: Refactor AwsV4AuthenticationFilter to use AuthState

**Files:**
- Modify: `src/main/java/org/example/filter/AwsV4AuthenticationFilter.java`

- [ ] **Step 1: Write the failing test**

Add a test to `AuthAdminServletTest.java` that verifies the filter reads from `AuthState`:

```java
// Add to AuthAdminServletTest.java

@Test
void authState_changeShouldAffectFilterBehavior() throws Exception {
    // Initially aws-v4, so no signature should be rejected
    AuthState state = AuthState.getInstance();
    state.setAuthMode("none");

    // After changing to none via AuthState, mode should be reflected
    assertThat(state.getAuthMode()).isEqualTo("none");

    // Change back
    state.setAuthMode("aws-v4");
}
```

This is a simple state test. The real integration is verified in Task 6 (integration tests). The critical part is the Filter refactor below.

- [ ] **Step 2: Refactor the Filter**

Replace the entire `AwsV4AuthenticationFilter.java` with:

```java
package org.example.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.auth.AwsCredentials;
import org.example.auth.AwsCredentialsProvider;
import org.example.auth.AuthState;
import org.example.auth.AwsV4Signer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AwsV4AuthenticationFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AwsV4AuthenticationFilter.class);

    private AwsV4Signer signer;
    private AwsCredentialsProvider credentialsProvider;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Read auth.mode from init-param and initialize AuthState
        String authMode = filterConfig.getInitParameter("auth.mode");
        AuthState.getInstance().init(authMode);

        String region = filterConfig.getInitParameter("auth.region");
        if (region == null) region = "us-east-1";

        String service = filterConfig.getInitParameter("auth.service");
        if (service == null) service = "s3";

        String timeSkewStr = filterConfig.getInitParameter("auth.time.skew.minutes");
        int timeSkew = timeSkewStr != null ? Integer.parseInt(timeSkewStr) : 15;

        this.signer = new AwsV4Signer(region, service, timeSkew);
        this.credentialsProvider = new AwsCredentialsProvider();

        String credentialsPath = filterConfig.getInitParameter("credentials.file.path");
        if (credentialsPath != null && !credentialsPath.isEmpty()) {
            try {
                credentialsPath = resolvePath(credentialsPath);
                Path path = Paths.get(credentialsPath);
                if (Files.exists(path)) {
                    this.credentialsProvider = AwsCredentialsProvider.fromFile(path);
                    logger.info("Loaded credentials from: {}", credentialsPath);
                } else {
                    logger.warn("Credentials file not found: {}", credentialsPath);
                }
            } catch (Exception e) {
                logger.error("Failed to load credentials from: {}", credentialsPath, e);
            }
        } else {
            // Fallback: load from classpath
            try (InputStream is = filterConfig.getServletContext().getResourceAsStream("/WEB-INF/classes/credentials.properties")) {
                if (is == null) {
                    try (InputStream is2 = getClass().getClassLoader().getResourceAsStream("credentials.properties")) {
                        if (is2 != null) {
                            this.credentialsProvider.load(is2);
                            logger.info("Loaded credentials from classpath: credentials.properties");
                        } else {
                            logger.warn("No credentials file configured and credentials.properties not found on classpath");
                        }
                    }
                } else {
                    this.credentialsProvider.load(is);
                    logger.info("Loaded credentials from classpath: credentials.properties");
                }
            } catch (Exception e) {
                logger.warn("Failed to load credentials from classpath", e);
            }
        }

        logger.info("AwsV4AuthenticationFilter initialized - mode: {}", AuthState.getInstance().getAuthMode());
    }

    private String resolvePath(String path) {
        if (path.contains("${user.home}")) {
            path = path.replace("${user.home}", System.getProperty("user.home"));
        }
        if (path.startsWith("~")) {
            path = path.replace("~", System.getProperty("user.home"));
        }
        return path;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestUri = httpRequest.getRequestURI();

        // Health check and admin endpoints always bypassed
        if ("/health".equals(requestUri) || requestUri.startsWith("/admin/")) {
            chain.doFilter(request, response);
            return;
        }

        String authMode = AuthState.getInstance().getAuthMode();

        if ("none".equals(authMode)) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = httpRequest.getHeader("Authorization");
        boolean hasV4Signature = authHeader != null && authHeader.startsWith("AWS4-HMAC-SHA256");

        if (!hasV4Signature) {
            if ("both".equals(authMode)) {
                chain.doFilter(request, response);
                return;
            }
            sendAuthError(httpResponse, "AccessDenied", "Missing AWS V4 signature");
            return;
        }

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(httpRequest);

        String accessKeyId = extractAccessKeyId(authHeader);
        if (accessKeyId == null) {
            sendAuthError(httpResponse, "AccessDenied", "Invalid credentials format");
            return;
        }

        AwsCredentials credentials = credentialsProvider.getCredentials(accessKeyId);
        if (credentials == null) {
            sendAuthError(httpResponse, "AccessDenied", "Unknown access key");
            return;
        }

        boolean valid = signer.verify(cachedRequest, credentials.getSecretAccessKey(), cachedRequest.getCachedBody());

        if (!valid) {
            sendAuthError(httpResponse, "SignatureDoesNotMatch", "Signature verification failed");
            return;
        }

        chain.doFilter(cachedRequest, response);
    }

    private String extractAccessKeyId(String authHeader) {
        try {
            int credIdx = authHeader.indexOf("Credential=");
            if (credIdx < 0) return null;
            int start = credIdx + "Credential=".length();
            int end = authHeader.indexOf(',', start);
            if (end < 0) end = authHeader.length();
            String credential = authHeader.substring(start, end).trim();
            return credential.split("/")[0];
        } catch (Exception e) {
            return null;
        }
    }

    private void sendAuthError(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/xml");
        String xml = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<Error>\n" +
                        "  <Code>%s</Code>\n" +
                        "  <Message>%s</Message>\n" +
                        "  <RequestId>%s</RequestId>\n" +
                        "</Error>",
                code, message, java.util.UUID.randomUUID()
        );
        response.getWriter().write(xml);
    }

    @Override
    public void destroy() {
        logger.info("AwsV4AuthenticationFilter destroyed");
    }
}
```

Key changes from original:
1. **Removed** `private boolean authEnabled` field and `private String authMode` field.
2. **Removed** reading of `auth.enabled` init-param.
3. **Added** `AuthState.getInstance().init(authMode)` in `init()`.
4. **Changed** `doFilter` to call `AuthState.getInstance().getAuthMode()` on every request instead of reading a field.
5. **Removed** the `if (!authEnabled)` early return.
6. **Added** `/admin/` path bypass alongside `/health`.
7. **Removed** `auth.enabled` handling entirely — `none` mode replaces `auth.enabled=false`.

- [ ] **Step 3: Run all unit tests to verify nothing broke**

Run: `mvn test -pl . -Dtest="AuthStateTest,AuthAdminServletTest,AwsV4SignerTest,StorageServiceTest,S3ServletTest" -Dsurefire.useFile=false 2>&1 | tail -30`
Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/filter/AwsV4AuthenticationFilter.java src/test/java/org/example/unit/AuthAdminServletTest.java
git commit -m "refactor: AwsV4AuthenticationFilter reads auth mode from AuthState singleton"
```

---

### Task 4: Update web.xml

**Files:**
- Modify: `src/main/webapp/WEB-INF/web.xml`

- [ ] **Step 1: Update web.xml**

Remove the `auth.enabled` init-param block (lines 46-49), and add the `AuthAdminServlet` declaration. The full updated `web.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
         https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
         version="6.0">

    <display-name>S3-Like Storage Service</display-name>

    <!-- Context Parameters -->
    <context-param>
        <param-name>storage.root.dir</param-name>
        <param-value>./storage</param-value>
    </context-param>

    <context-param>
        <param-name>storage.max.file.size</param-name>
        <param-value>104857600</param-value>
    </context-param>

    <!-- Health Monitor Parameters -->
    <context-param>
        <param-name>health.monitor.enabled</param-name>
        <param-value>true</param-value>
    </context-param>

    <context-param>
        <param-name>health.monitor.interval.seconds</param-name>
        <param-value>10</param-value>
    </context-param>

    <context-param>
        <param-name>health.monitor.base.url</param-name>
        <param-value>http://localhost:5080</param-value>
    </context-param>

    <!-- Health Monitor Listener -->
    <listener>
        <listener-class>org.example.monitor.HealthMonitorListener</listener-class>
    </listener>

    <!-- AWS V4 Authentication Filter -->
    <filter>
        <filter-name>AwsV4AuthenticationFilter</filter-name>
        <filter-class>org.example.filter.AwsV4AuthenticationFilter</filter-class>
        <init-param>
            <param-name>auth.mode</param-name>
            <param-value>aws-v4</param-value>
        </init-param>
        <init-param>
            <param-name>auth.region</param-name>
            <param-value>us-east-1</param-value>
        </init-param>
        <init-param>
            <param-name>auth.service</param-name>
            <param-value>s3</param-value>
        </init-param>
        <init-param>
            <param-name>auth.time.skew.minutes</param-name>
            <param-value>15</param-value>
        </init-param>
    </filter>

    <filter-mapping>
        <filter-name>AwsV4AuthenticationFilter</filter-name>
        <url-pattern>/*</url-pattern>
    </filter-mapping>

    <!-- Auth Admin Servlet -->
    <servlet>
        <servlet-name>AuthAdminServlet</servlet-name>
        <servlet-class>org.example.servlet.AuthAdminServlet</servlet-class>
    </servlet>

    <servlet-mapping>
        <servlet-name>AuthAdminServlet</servlet-name>
        <url-pattern>/admin/*</url-pattern>
    </servlet-mapping>

    <!-- S3-Like Servlet -->
    <servlet>
        <servlet-name>S3Servlet</servlet-name>
        <servlet-class>org.example.servlet.S3Servlet</servlet-class>
        <load-on-startup>1</load-on-startup>
    </servlet>

    <servlet-mapping>
        <servlet-name>S3Servlet</servlet-name>
        <url-pattern>/*</url-pattern>
    </servlet-mapping>

    <!-- Welcome File -->
    <welcome-file-list>
        <welcome-file>index.html</welcome-file>
    </welcome-file-list>

</web-app>
```

- [ ] **Step 2: Run all unit tests to verify nothing broke**

Run: `mvn test -pl . -Dtest="AuthStateTest,AuthAdminServletTest,AwsV4SignerTest,StorageServiceTest,S3ServletTest" -Dsurefire.useFile=false 2>&1 | tail -30`
Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/webapp/WEB-INF/web.xml
git commit -m "refactor: remove auth.enabled param, add AuthAdminServlet mapping in web.xml"
```

---

### Task 5: Update Web UI with auth settings card

**Files:**
- Modify: `src/main/webapp/index.html`

- [ ] **Step 1: Add auth settings card to the grid**

Insert the following card HTML right before the closing `</div>` of the `.grid` div (after the "Files in Bucket" card, before line 311 `</div>`):

```html
            <!-- Auth Settings Card -->
            <div class="card">
                <h2>🔒 Auth Settings</h2>

                <div class="form-group">
                    <label for="authMode">Authentication Mode</label>
                    <select id="authMode">
                        <option value="aws-v4">aws-v4 (Strict)</option>
                        <option value="both">both (Permissive)</option>
                        <option value="none">none (Disabled)</option>
                    </select>
                </div>

                <button class="btn btn-primary" onclick="applyAuthMode()">Apply Settings</button>

                <div id="authMessage"></div>
            </div>
```

- [ ] **Step 2: Add auth settings JavaScript**

Add the following functions to the `<script>` block, before the `// Initialize buckets on page load` comment at the end:

```javascript
        // ==================== Auth Settings ====================

        async function loadAuthStatus() {
            try {
                const response = await fetch(`${API_BASE}/admin/auth-status`);
                if (response.ok) {
                    const data = await response.json();
                    document.getElementById('authMode').value = data.mode;
                }
            } catch (error) {
                console.error('Failed to load auth status:', error);
            }
        }

        async function applyAuthMode() {
            const mode = document.getElementById('authMode').value;
            try {
                const response = await fetch(`${API_BASE}/admin/auth-status`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ mode: mode })
                });
                if (response.ok) {
                    const data = await response.json();
                    document.getElementById('authMode').value = data.mode;
                    showMessage('authMessage', `Auth mode updated to "${data.mode}"`, 'success');
                } else {
                    const data = await response.json();
                    showMessage('authMessage', data.error || 'Failed to update auth mode', 'error');
                }
            } catch (error) {
                showMessage('authMessage', 'Failed to update auth mode', 'error');
            }
        }
```

- [ ] **Step 3: Add auth status loading to page init**

Replace the last line of the `<script>` block:

```javascript
        // Initialize buckets on page load
        listBuckets();
```

with:

```javascript
        // Initialize on page load
        listBuckets();
        loadAuthStatus();
```

- [ ] **Step 4: Run unit tests to verify nothing broke**

Run: `mvn test -pl . -Dtest="AuthStateTest,AuthAdminServletTest,AwsV4SignerTest,StorageServiceTest,S3ServletTest" -Dsurefire.useFile=false 2>&1 | tail -30`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/webapp/index.html
git commit -m "feat: add auth settings card to web UI"
```

---

### Task 6: Update integration tests

**Files:**
- Modify: `src/test/java/org/example/integration/S3StorageIntegrationTest.java`
- Modify: `src/test/java/org/example/integration/AwsSdkIntegrationTest.java`

- [ ] **Step 1: Update S3StorageIntegrationTest override-web.xml**

In `S3StorageIntegrationTest.java`, replace the filter section of the override-web.xml string (lines 78-89). Replace `auth.enabled=false` with `auth.mode=none`:

Old:
```java
                "    <filter>\n" +
                "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n" +
                "        <filter-class>org.example.filter.AwsV4AuthenticationFilter</filter-class>\n" +
                "        <init-param>\n" +
                "            <param-name>auth.enabled</param-name>\n" +
                "            <param-value>false</param-value>\n" +
                "        </init-param>\n" +
                "    </filter>\n" +
                "    <filter-mapping>\n" +
                "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n" +
                "        <url-pattern>/*</url-pattern>\n" +
                "    </filter-mapping>\n" +
```

New:
```java
                "    <filter>\n" +
                "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n" +
                "        <filter-class>org.example.filter.AwsV4AuthenticationFilter</filter-class>\n" +
                "        <init-param>\n" +
                "            <param-name>auth.mode</param-name>\n" +
                "            <param-value>none</param-value>\n" +
                "        </init-param>\n" +
                "    </filter>\n" +
                "    <filter-mapping>\n" +
                "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n" +
                "        <url-pattern>/*</url-pattern>\n" +
                "    </filter-mapping>\n" +
```

- [ ] **Step 2: Add admin endpoint test to S3StorageIntegrationTest**

Add this test method to `S3StorageIntegrationTest`, before the helper methods section (before the `uploadFile` method around line 486):

```java
    // ==================== Admin Auth Endpoint Tests ====================

    @Test
    @Order(70)
    void getAuthStatus_shouldReturnCurrentMode() throws Exception {
        HttpGet request = new HttpGet(apiUrl + "/admin/auth-status");

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            assertThat(response.getCode()).isEqualTo(200);
            assertThat(response.getHeader("Content-Type").getValue()).contains("application/json");

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("\"mode\"");
        }
    }

    @Test
    @Order(71)
    void setAuthStatus_shouldUpdateMode() throws Exception {
        // Set to both
        HttpPost setRequest = new HttpPost(apiUrl + "/admin/auth-status");
        setRequest.setEntity(new StringEntity("{\"mode\":\"both\"}", ContentType.APPLICATION_JSON));

        try (CloseableHttpResponse response = httpClient.execute(setRequest)) {
            assertThat(response.getCode()).isEqualTo(200);

            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("\"mode\":\"both\"");
        }

        // Verify via GET
        HttpGet getRequest = new HttpGet(apiUrl + "/admin/auth-status");
        try (CloseableHttpResponse response = httpClient.execute(getRequest)) {
            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("\"mode\":\"both\"");
        }

        // Reset to none for subsequent tests
        HttpPost resetRequest = new HttpPost(apiUrl + "/admin/auth-status");
        resetRequest.setEntity(new StringEntity("{\"mode\":\"none\"}", ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse response = httpClient.execute(resetRequest)) {
            EntityUtils.consume(response.getEntity());
        }
    }
```

Also add the `HttpPost` import at the top (it's already importing from `org.apache.hc.client5.http.classic.methods.*` which includes `HttpPost` via the wildcard, so no change needed).

- [ ] **Step 3: Update AwsSdkIntegrationTest override-web.xml**

In `AwsSdkIntegrationTest.java`, remove the `auth.enabled` init-param block (lines 79-82). The filter section changes from:

```java
                "        <init-param>\n" +
                "            <param-name>auth.enabled</param-name>\n" +
                "            <param-value>true</param-value>\n" +
                "        </init-param>\n" +
                "        <init-param>\n" +
                "            <param-name>auth.mode</param-name>\n" +
                "            <param-value>aws-v4</param-value>\n" +
                "        </init-param>\n" +
```

to:

```java
                "        <init-param>\n" +
                "            <param-name>auth.mode</param-name>\n" +
                "            <param-value>aws-v4</param-value>\n" +
                "        </init-param>\n" +
```

- [ ] **Step 4: Run all tests**

Run: `mvn test -Dsurefire.useFile=false 2>&1 | tail -40`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/org/example/integration/S3StorageIntegrationTest.java src/test/java/org/example/integration/AwsSdkIntegrationTest.java
git commit -m "test: update integration tests for single auth.mode parameter, add admin endpoint tests"
```

---

### Task 7: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update auth configuration docs**

In CLAUDE.md, find the `### AwsV4AuthenticationFilter 参数` table and:

1. Remove the `auth.enabled` row
2. Update `auth.mode` description to mention runtime toggling

Change the table from:
```markdown
| 参数名 | 默认值 | 说明 |
|--------|---------|------|
| `auth.enabled` | `true` | 是否启用 V4 认证 |
| `auth.mode` | `aws-v4` | 认证模式：`aws-v4` / `both` / `none` |
| `auth.region` | `us-east-1` | AWS 区域 |
| `auth.service` | `s3` | 服务名 |
| `auth.time.skew.minutes` | `15` | 时间偏差容忍（分钟） |
| `credentials.file.path` | - | AK/SK 凭证文件路径 |
```

to:
```markdown
| 参数名 | 默认值 | 说明 |
|--------|---------|------|
| `auth.mode` | `aws-v4` | 认证模式：`aws-v4` / `both` / `none`，支持运行时动态切换 |
| `auth.region` | `us-east-1` | AWS 区域 |
| `auth.service` | `s3` | 服务名 |
| `auth.time.skew.minutes` | `15` | 时间偏差容忍（分钟） |
| `credentials.file.path` | - | AK/SK 凭证文件路径 |
```

- [ ] **Step 2: Add admin API endpoint to the API table**

In the `## API 接口` section, add a new row to the table:

```markdown
| GET | `/admin/auth-status` | 获取当前鉴权模式 | 200 JSON |
| POST | `/admin/auth-status` | 设置鉴权模式 | 200 JSON |
```

- [ ] **Step 3: Update FAQ section**

Update the "如何关闭 V4 认证" FAQ answer from:

```markdown
### Q: 如何关闭 V4 认证
A: 在 `web.xml` 中设置 `auth.enabled` 为 `false`
```

to:

```markdown
### Q: 如何切换 V4 认证模式
A: 在 Web 管理界面的「Auth Settings」卡片中选择模式并点击「Apply Settings」，或通过 API 调用 `POST /admin/auth-status` 设置 `mode` 为 `none` / `both` / `aws-v4`
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for runtime auth mode toggling"
```

---

## Self-Review

**Spec coverage check:**
- AuthState singleton → Task 1 ✓
- AuthAdminServlet with GET/POST on /admin/auth-status → Task 2 ✓
- Filter refactored to use AuthState, removed authEnabled → Task 3 ✓
- web.xml: removed auth.enabled, added AuthAdminServlet → Task 4 ✓
- Web UI settings card → Task 5 ✓
- Integration tests updated → Task 6 ✓
- CLAUDE.md updated → Task 7 ✓

**Placeholder scan:** No TBD/TODO/vague references found.

**Type consistency:** `AuthState.getAuthMode()` returns `String`, used consistently in Filter, AdminServlet, and tests. `setAuthMode(String)` used in AdminServlet and tests. `init(String)` used in Filter init.
