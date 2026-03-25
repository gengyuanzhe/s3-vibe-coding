package org.example.servlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

/**
 * S3-like Servlet for handling file upload and download operations
 * Supports S3-style bucket and object operations
 */
public class S3Servlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(S3Servlet.class);

    private StorageService storageService;
    private long maxFileSize;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        String storageRootDir = config.getServletContext().getInitParameter("storage.root.dir");
        if (storageRootDir == null) {
            storageRootDir = "./storage";
        }

        String maxSizeParam = config.getServletContext().getInitParameter("storage.max.file.size");
        this.maxFileSize = maxSizeParam != null ? Long.parseLong(maxSizeParam) : 100 * 1024 * 1024; // 100MB default

        this.storageService = new StorageService(storageRootDir, maxFileSize);

        logger.info("S3Servlet initialized with storage directory: {}", storageRootDir);
    }

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
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        handlePostRequest(req, resp);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Handle CORS preflight requests
        setCorsHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Handle GET requests - List buckets, List objects, or Download object
     */
    private void handleGetRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();
        String queryString = req.getQueryString();

        logger.info("GET request: path={}, query={}", pathInfo, queryString);

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
        xml.append("<ListAllMyBucketsResult>\n");
        xml.append("  <Owner>\n");
        xml.append("    <ID>s3-storage</ID>\n");
        xml.append("    <DisplayName>S3 Storage Service</DisplayName>\n");
        xml.append("  </Owner>\n");
        xml.append("  <Buckets>\n");

        for (String bucket : buckets) {
            xml.append("    <Bucket>\n");
            xml.append("      <Name>").append(escapeXml(bucket)).append("</Name>\n");
            xml.append("      <CreationDate>2024-01-01T00:00:00.000Z</CreationDate>\n");
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
        xml.append("<ListBucketResult>\n");
        xml.append("  <Name>").append(escapeXml(bucketName)).append("</Name>\n");
        xml.append("  <Prefix>").append(prefix != null ? escapeXml(prefix) : "").append("</Prefix>\n");
        xml.append("  <MaxKeys>1000</MaxKeys>\n");
        xml.append("  <IsTruncated>false</IsTruncated>\n");
        xml.append("  <Contents>\n");

        for (var object : objects) {
            xml.append("    <Contents>\n");
            xml.append("      <Key>").append(escapeXml(object.getKey())).append("</Key>\n");
            xml.append("      <Size>").append(object.getSize()).append("</Size>\n");
            xml.append("      <LastModified>").append(object.getLastModifiedFormatted()).append("</LastModified>\n");
            xml.append("    </Contents>\n");
        }

        xml.append("  </Contents>\n");
        xml.append("</ListBucketResult>");

        sendXmlResponse(resp, xml.toString());
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
        if (file == null) {
            sendError(resp, "NoSuchKey", "The specified key does not exist");
            return;
        }

        var metadata = storageService.getObjectMetadata(bucketName, objectKey);
        String contentType = metadata != null ? metadata.getContentType() : "application/octet-stream";

        resp.setContentType(contentType);
        resp.setContentLengthLong(file.length());
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + objectKey + "\"");

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
     * Create a bucket
     */
    private void handleCreateBucket(HttpServletRequest req, HttpServletResponse resp, String bucketName) throws IOException {
        if (storageService.createBucket(bucketName)) {
            sendSuccessResponse(resp, "Bucket created successfully");
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

        boolean success = storageService.putObject(bucketName, objectKey, req.getInputStream(), contentLength);

        if (success) {
            String etag = java.util.UUID.randomUUID().toString();
            resp.setHeader("ETag", "\"" + etag + "\"");
            sendSuccessResponse(resp, "Object uploaded successfully");
        } else {
            sendError(resp, "InternalError", "Failed to upload object");
        }
    }

    /**
     * Delete a bucket
     */
    private void handleDeleteBucket(HttpServletRequest req, HttpServletResponse resp, String bucketName) throws IOException {
        if (storageService.deleteBucket(bucketName)) {
            sendSuccessResponse(resp, "Bucket deleted successfully");
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
            sendSuccessResponse(resp, "Object deleted successfully");
        } else {
            sendError(resp, "NoSuchKey", "The specified key does not exist");
        }
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
     * Send success response
     */
    private void sendSuccessResponse(HttpServletResponse resp, String message) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"status\":\"success\",\"message\":\"" + escapeJson(message) + "\"}");
    }

    /**
     * Send error response in S3 XML format
     */
    private void sendError(HttpServletResponse resp, String code, String message) throws IOException {
        resp.setStatus(getStatusCodeForError(code));
        resp.setContentType("application/xml");
        String xml = String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<Error>\n" +
                        "  <Code>%s</Code>\n" +
                        "  <Message>%s</Message>\n" +
                        "  <Resource>%s</Resource>\n" +
                        "  <RequestId>%s</RequestId>\n" +
                        "</Error>",
                code, escapeXml(message), escapeXml(code), java.util.UUID.randomUUID()
        );
        resp.getWriter().write(xml);
    }

    /**
     * Map S3 error codes to HTTP status codes
     */
    private int getStatusCodeForError(String code) {
        return switch (code) {
            case "AccessDenied", "InvalidToken" -> HttpServletResponse.SC_UNAUTHORIZED;
            case "NoSuchBucket", "NoSuchKey" -> HttpServletResponse.SC_NOT_FOUND;
            case "EntityTooLarge" -> HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE;
            case "InvalidBucketName" -> HttpServletResponse.SC_BAD_REQUEST;
            default -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Set CORS headers
     */
    private void setCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, x-amz-*");
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
     * Escape JSON special characters
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
