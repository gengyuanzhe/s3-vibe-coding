package org.example.servlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.service.OriginConfig;
import org.example.service.OriginProxyService;

import java.io.BufferedReader;
import java.io.IOException;

public class OriginConfigServlet extends HttpServlet {

    private OriginProxyService originProxyService;

    @Override
    public void init(ServletConfig config) {
        String storageRootDir = config.getServletContext().getInitParameter("storage.root.dir");
        this.originProxyService = new OriginProxyService(storageRootDir);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String bucketName = extractBucketName(req);
        if (bucketName == null || bucketName.isEmpty()) {
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Bucket name is required\"}");
            return;
        }

        OriginConfig config = originProxyService.getOriginConfig(bucketName);
        if (config == null) {
            resp.setStatus(404);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"No origin config found for bucket\"}");
            return;
        }

        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getWriter().write(config.toJson());
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String bucketName = extractBucketName(req);
        if (bucketName == null || bucketName.isEmpty()) {
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Bucket name is required\"}");
            return;
        }

        String body = readBody(req);
        OriginConfig config = OriginConfig.fromJson(body);
        if (config == null || !config.validate()) {
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Invalid origin config. Required: originUrl, originBucket, valid cachePolicy (no-cache, cache, cache-ttl)\"}");
            return;
        }

        originProxyService.saveOriginConfig(bucketName, config);

        resp.setStatus(200);
        resp.setContentType("application/json");
        resp.getWriter().write(config.toJson());
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String bucketName = extractBucketName(req);
        if (bucketName == null || bucketName.isEmpty()) {
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Bucket name is required\"}");
            return;
        }

        originProxyService.deleteOriginConfig(bucketName);
        resp.setStatus(204);
    }

    private String extractBucketName(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isEmpty()) {
            return null;
        }
        // Remove leading "/"
        if (pathInfo.startsWith("/")) {
            pathInfo = pathInfo.substring(1);
        }
        return pathInfo.isEmpty() ? null : pathInfo;
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
}
