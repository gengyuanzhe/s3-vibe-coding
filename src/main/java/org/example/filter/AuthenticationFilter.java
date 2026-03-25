package org.example.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Authentication Filter for verifying requests
 * Can be enabled/disabled via init parameters
 */
public class AuthenticationFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationFilter.class);

    private boolean authEnabled;
    private String authHeader;
    private String authToken;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.authEnabled = Boolean.parseBoolean(filterConfig.getInitParameter("auth.enabled"));
        this.authHeader = filterConfig.getInitParameter("auth.header");
        this.authToken = filterConfig.getInitParameter("auth.token");

        logger.info("AuthenticationFilter initialized - Enabled: {}", authEnabled);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Skip authentication for OPTIONS requests (CORS preflight)
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // If authentication is disabled, pass through
        if (!authEnabled) {
            chain.doFilter(request, response);
            return;
        }

        // Check for authorization header
        String providedToken = httpRequest.getHeader(authHeader);

        if (authToken == null || !authToken.equals(providedToken)) {
            logger.warn("Unauthorized request from: {}", httpRequest.getRemoteAddr());

            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/xml");
            httpResponse.getWriter().write(generateErrorResponse("AccessDenied", "Invalid authentication token"));
            return;
        }

        // Authentication successful, proceed
        chain.doFilter(request, response);
    }

    /**
     * Generate S3-style error response in XML format
     */
    private String generateErrorResponse(String code, String message) {
        return String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<Error>\n" +
                        "  <Code>%s</Code>\n" +
                        "  <Message>%s</Message>\n" +
                        "  <RequestId>%s</RequestId>\n" +
                        "</Error>",
                code, message, java.util.UUID.randomUUID()
        );
    }

    @Override
    public void destroy() {
        logger.info("AuthenticationFilter destroyed");
    }
}
