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
