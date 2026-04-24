package org.example.servlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.service.OriginProxyService;
import org.example.service.OriginProxyService.ProxyResult;
import org.example.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * S3-like Servlet for handling file upload and download operations
 * Supports S3-style bucket and object operations with AWS SDK compatibility
 */
public class S3Servlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(S3Servlet.class);
    private static final String S3_XMLNS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private StorageService storageService;
    private long maxFileSize;
    private OriginProxyService originProxyService;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handleGetRequest(req, resp);
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handlePutRequest(req, resp);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handleDeleteRequest(req, resp);
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handleHeadRequest(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handlePostRequest(req, resp);
    }

    private ServletConfig servletConfig;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        this.servletConfig = config;

        String storageRootDir = config.getServletContext().getInitParameter("storage.root.dir");
        if (storageRootDir == null) {
            storageRootDir = "./storage";
        }

        String maxSizeParam = config.getServletContext().getInitParameter("storage.max.file.size");
        this.maxFileSize = maxSizeParam != null ? Long.parseLong(maxSizeParam) : 100 * 1024 * 1024; // 100MB default

        this.storageService = new StorageService(storageRootDir, maxFileSize);
        this.originProxyService = new OriginProxyService(storageRootDir);

        logger.info("S3Servlet initialized with storage directory: {}", storageRootDir);
    }

    /**
     * Handle GET requests - List buckets, List objects, or Download object
     */
    private void handleGetRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        String pathInfo = req.getPathInfo();
        String queryString = req.getQueryString();

        logger.info("GET request: path={}, query={}", pathInfo, queryString);

        // Serve web UI static files
        if ("/index.html".equals(pathInfo)
                || (pathInfo != null && pathInfo.endsWith(".css"))
                || (pathInfo != null && pathInfo.endsWith(".js"))) {
            serveStaticFile(resp, pathInfo);
            return;
        }

        // Serve web UI for root path with text/html accept
        if ((pathInfo == null || pathInfo.equals("/"))
                && req.getHeader("Accept") != null && req.getHeader("Accept").contains("text/html")) {
            serveStaticFile(resp, "/index.html");
            return;
        }

        // Health check endpoint
        if ("/health".equals(pathInfo)) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"status\":\"ok\",\"service\":\"s3-storage\"}");
            return;
        }

        // Root path - list all buckets
        if (pathInfo == null || pathInfo.equals("/")) {
            handleListBuckets(req, resp);
            return;
        }

        // Extract bucket name and object key
        String[] pathParts = pathInfo.substring(1).split("/", 2);
        String bucketName = pathParts[0];
        String objectKey = pathParts.length > 1 ? pathParts[1] : null;

        // Check if object key is present
        if (objectKey != null && !objectKey.isEmpty()) {
            // Download specific object
            handleGetObject(req, resp, bucketName, objectKey);
        } else {
            // List objects in bucket
            handleListObjects(req, resp, bucketName);
        }
    }

    /**
     * Handle PUT requests - Create bucket or Upload object
     */
    private void handlePutRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        logger.info("PUT request: path={}", pathInfo);

        if (pathInfo == null || pathInfo.equals("/")) {
            sendError(resp, "InvalidBucketName", "Bucket name is required");
            return;
        }

        String[] pathParts = pathInfo.substring(1).split("/", 2);
        String bucketName = pathParts[0];
        String objectKey = pathParts.length > 1 ? pathParts[1] : null;

        if (objectKey == null || objectKey.isEmpty()) {
            // Create bucket
            handleCreateBucket(req, resp, bucketName);
        } else {
            // Upload object
            handlePutObject(req, resp, bucketName, objectKey);
        }
    }

    /**
     * Handle DELETE requests - Delete bucket or Delete object
     */
    private void handleDeleteRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        logger.info("DELETE request: path={}", pathInfo);

        if (pathInfo == null || pathInfo.equals("/")) {
            sendError(resp, "InvalidRequest", "Resource path is required");
            return;
        }

        String[] pathParts = pathInfo.substring(1).split("/", 2);
        String bucketName = pathParts[0];
        String objectKey = pathParts.length > 1 ? pathParts[1] : null;

        if (objectKey == null || objectKey.isEmpty()) {
            // Delete bucket
            handleDeleteBucket(req, resp, bucketName);
        } else {
            // Delete object
            handleDeleteObject(req, resp, bucketName, objectKey);
        }
    }

    /**
     * Handle POST requests - Additional operations (if needed)
     */
    private void handlePostRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        logger.info("POST request: path={}", pathInfo);

        // For now, treat POST similar to PUT for object uploads
        handlePutRequest(req, resp);
    }

    /**
     * List all buckets
     */
    private void handleListBuckets(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var buckets = storageService.listBuckets();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<ListAllMyBucketsResult xmlns=\"").append(S3_XMLNS).append("\">\n");
        xml.append("  <Owner>\n");
        xml.append("    <ID>s3-storage</ID>\n");
        xml.append("    <DisplayName>S3 Storage Service</DisplayName>\n");
        xml.append("  </Owner>\n");
        xml.append("  <Buckets>\n");

        for (var bucket : buckets) {
            xml.append("    <Bucket>\n");
            xml.append("      <Name>").append(escapeXml(bucket.getName())).append("</Name>\n");
            xml.append("      <CreationDate>").append(bucket.getCreationDateFormatted()).append("</CreationDate>\n");
            xml.append("    </Bucket>\n");
        }

        xml.append("  </Buckets>\n");
        xml.append("</ListAllMyBucketsResult>");

        sendXmlResponse(resp, xml.toString());
    }

    /**
     * List objects in a bucket
     */
    private void handleListObjects(HttpServletRequest req, HttpServletResponse resp, String bucketName) throws IOException {
        if (!storageService.bucketExists(bucketName)) {
            sendError(resp, "NoSuchBucket", "The specified bucket does not exist");
            return;
        }

        String prefix = req.getParameter("prefix");
        var objects = storageService.listObjects(bucketName, prefix);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<ListBucketResult xmlns=\"").append(S3_XMLNS).append("\">\n");
        xml.append("  <Name>").append(escapeXml(bucketName)).append("</Name>\n");
        xml.append("  <Prefix>").append(prefix != null ? escapeXml(prefix) : "").append("</Prefix>\n");
        xml.append("  <MaxKeys>1000</MaxKeys>\n");
        xml.append("  <IsTruncated>false</IsTruncated>\n");

        for (var object : objects) {
            String etag = storageService.getObjectEtag(bucketName, object.getKey());
            xml.append("  <Contents>\n");
            xml.append("    <Key>").append(escapeXml(object.getKey())).append("</Key>\n");
            xml.append("    <Size>").append(object.getSize()).append("</Size>\n");
            xml.append("    <LastModified>").append(object.getLastModifiedFormatted()).append("</LastModified>\n");
            if (etag != null) {
                xml.append("    <ETag>\"").append(escapeXml(etag)).append("\"</ETag>\n");
            }
            xml.append("  </Contents>\n");
        }

        xml.append("</ListBucketResult>");

        sendXmlResponse(resp, xml.toString());
    }

    /**
     * Handle HEAD requests - Check object existence and metadata
     */
    private void handleHeadRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        logger.info("HEAD request: path={}", pathInfo);

        if (pathInfo == null || pathInfo.equals("/")) {
            resp.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String[] pathParts = pathInfo.substring(1).split("/", 2);
        String bucketName = pathParts[0];
        String objectKey = pathParts.length > 1 ? pathParts[1] : null;

        if (objectKey != null && !objectKey.isEmpty()) {
            handleHeadObject(req, resp, bucketName, objectKey);
        } else {
            resp.setStatus(HttpServletResponse.SC_OK);
        }
    }

    /**
     * Handle HEAD for a specific object - return metadata without body
     */
    private void handleHeadObject(HttpServletRequest req, HttpServletResponse resp, String bucketName, String objectKey) throws IOException {
        if (!storageService.bucketExists(bucketName)) {
            sendError(resp, "NoSuchBucket", "The specified bucket does not exist");
            return;
        }

        File file = storageService.getObject(bucketName, objectKey);
        if (file != null) {
            var metadata = storageService.getObjectMetadata(bucketName, objectKey);
            String contentType = metadata != null ? metadata.getContentType() : "application/octet-stream";
            resp.setContentType(contentType);
            resp.setContentLengthLong(file.length());
            resp.setHeader("Last-Modified", formatRfc1123Date(file.lastModified()));
            String etag = storageService.getObjectEtag(bucketName, objectKey);
            if (etag != null) {
                resp.setHeader("ETag", "\"" + etag + "\"");
            }
            resp.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        // Local object not found - try origin proxy
        if (tryOriginProxy(resp, bucketName, objectKey, "HEAD", req.getQueryString())) {
            return;
        }

        sendError(resp, "NoSuchKey", "The specified key does not exist");
    }

    /**
     * Get/Download an object
     */
    private void handleGetObject(HttpServletRequest req, HttpServletResponse resp, String bucketName, String objectKey) throws IOException {
        if (!storageService.bucketExists(bucketName)) {
            sendError(resp, "NoSuchBucket", "The specified bucket does not exist");
            return;
        }

        File file = storageService.getObject(bucketName, objectKey);
        if (file != null) {
            serveLocalObject(resp, bucketName, objectKey, file);
            return;
        }

        // Local object not found - try origin proxy
        if (tryOriginProxy(resp, bucketName, objectKey, "GET", req.getQueryString())) {
            return;
        }

        sendError(resp, "NoSuchKey", "The specified key does not exist");
    }

    /**
     * Serve a local object file with appropriate headers
     */
    private void serveLocalObject(HttpServletResponse resp, String bucketName, String objectKey, File file) throws IOException {
        var metadata = storageService.getObjectMetadata(bucketName, objectKey);
        String contentType = metadata != null ? metadata.getContentType() : "application/octet-stream";
        resp.setContentType(contentType);
        resp.setContentLengthLong(file.length());
        resp.setHeader("Last-Modified", formatRfc1123Date(file.lastModified()));
        String etag = storageService.getObjectEtag(bucketName, objectKey);
        if (etag != null) {
            resp.setHeader("ETag", "\"" + etag + "\"");
        }
        try (InputStream in = new FileInputStream(file);
             OutputStream out = resp.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        logger.info("Downloaded object: {}/{} (size: {} bytes)", bucketName, objectKey, file.length());
    }

    /**
     * Try to proxy a request to the configured origin server.
     * @return true if proxy was attempted (regardless of result), false if no origin configured
     */
    private boolean tryOriginProxy(HttpServletResponse resp, String bucketName, String objectKey, String method, String queryString) throws IOException {
        ProxyResult result = originProxyService.proxyRequest(bucketName, objectKey, method, queryString);
        if (result == null) {
            return false;
        }
        resp.setStatus(result.getStatusCode());
        if (result.getErrorCode() != null && result.getStatusCode() >= 400) {
            if ("NoSuchKey".equals(result.getErrorCode())) {
                sendError(resp, "NoSuchKey", "The specified key does not exist");
            } else {
                sendError(resp, result.getErrorCode(), "Origin request failed");
            }
            return true;
        }
        if (result.getHeaders() != null) {
            for (var entry : result.getHeaders().entrySet()) {
                String key = entry.getKey();
                if (key.equalsIgnoreCase("transfer-encoding") || key.equalsIgnoreCase("connection")) continue;
                resp.setHeader(key, entry.getValue());
            }
        }
        if (result.getBody() != null) {
            resp.setContentLengthLong(result.getBody().length);
            resp.getOutputStream().write(result.getBody());
        }
        logger.info("Proxied {} {}/{} from origin (status: {})", method, bucketName, objectKey, result.getStatusCode());
        return true;
    }

    /**
     * Create a bucket
     */
    private void handleCreateBucket(HttpServletRequest req, HttpServletResponse resp, String bucketName) throws IOException {
        if (storageService.createBucket(bucketName)) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader("Location", "/" + bucketName);
        } else {
            sendError(resp, "BucketAlreadyExists", "The requested bucket name is not available");
        }
    }

    /**
     * Upload an object
     */
    private void handlePutObject(HttpServletRequest req, HttpServletResponse resp, String bucketName, String objectKey) throws IOException {
        if (!storageService.bucketExists(bucketName)) {
            sendError(resp, "NoSuchBucket", "The specified bucket does not exist");
            return;
        }

        long contentLength = req.getContentLength();
        if (contentLength > maxFileSize) {
            sendError(resp, "EntityTooLarge", "Your proposed upload exceeds the maximum allowed size");
            return;
        }

        String etag = storageService.putObject(bucketName, objectKey, req.getInputStream(), contentLength);

        if (etag != null) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setHeader("ETag", "\"" + etag + "\"");
        } else {
            sendError(resp, "InternalError", "Failed to upload object");
        }
    }

    /**
     * Delete a bucket
     */
    private void handleDeleteBucket(HttpServletRequest req, HttpServletResponse resp, String bucketName) throws IOException {
        if (storageService.deleteBucket(bucketName)) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } else if (!storageService.bucketExists(bucketName)) {
            sendError(resp, "NoSuchBucket", "The specified bucket does not exist");
        } else {
            sendError(resp, "BucketNotEmpty", "The bucket you tried to delete is not empty");
        }
    }

    /**
     * Delete an object
     */
    private void handleDeleteObject(HttpServletRequest req, HttpServletResponse resp, String bucketName, String objectKey) throws IOException {
        if (!storageService.bucketExists(bucketName)) {
            sendError(resp, "NoSuchBucket", "The specified bucket does not exist");
            return;
        }

        if (storageService.deleteObject(bucketName, objectKey)) {
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        // Local object not found - try origin proxy delete
        if (tryOriginProxy(resp, bucketName, objectKey, "DELETE", req.getQueryString())) {
            return;
        }

        sendError(resp, "NoSuchKey", "The specified key does not exist");
    }

    /**
     * Send XML response
     */
    private void sendXmlResponse(HttpServletResponse resp, String xml) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/xml");
        resp.getWriter().write(xml);
    }

    /**
     * Send error response in S3 XML format
     */
    private void sendError(HttpServletResponse resp, String code, String message) throws IOException {
        resp.setStatus(getStatusCodeForError(code));
        resp.setContentType("application/xml");
        String xml = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<Error xmlns=\"%s\">\n" +
                        "  <Code>%s</Code>\n" +
                        "  <Message>%s</Message>\n" +
                        "  <Resource>%s</Resource>\n" +
                        "  <RequestId>%s</RequestId>\n" +
                        "</Error>",
                S3_XMLNS, code, escapeXml(message), escapeXml(code), java.util.UUID.randomUUID()
        );
        resp.getWriter().write(xml);
    }

    /**
     * Map S3 error codes to HTTP status codes
     */
    private int getStatusCodeForError(String code) {
        return switch (code) {
            case "AccessDenied", "InvalidToken" -> HttpServletResponse.SC_FORBIDDEN;
            case "NoSuchBucket", "NoSuchKey" -> HttpServletResponse.SC_NOT_FOUND;
            case "BucketAlreadyExists", "BucketNotEmpty" -> HttpServletResponse.SC_CONFLICT;
            case "EntityTooLarge" -> HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE;
            case "InvalidBucketName" -> HttpServletResponse.SC_BAD_REQUEST;
            default -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Format date in RFC 1123 format for Last-Modified header
     */
    private String formatRfc1123Date(long millis) {
        DateTimeFormatter formatter = DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(ZoneId.of("GMT"));
        return formatter.format(Instant.ofEpochMilli(millis));
    }

    /**
     * Escape XML special characters
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Serve a static file from the webapp directory
     */
    private void serveStaticFile(HttpServletResponse resp, String path) throws IOException {
        try (InputStream is = servletConfig.getServletContext().getResourceAsStream(path)) {
            if (is != null) {
                byte[] data = is.readAllBytes();
                String contentType = "text/html";
                if (path.endsWith(".css")) contentType = "text/css";
                else if (path.endsWith(".js")) contentType = "application/javascript";
                resp.setContentType(contentType);
                resp.setContentLength(data.length);
                resp.getOutputStream().write(data);
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }
}
