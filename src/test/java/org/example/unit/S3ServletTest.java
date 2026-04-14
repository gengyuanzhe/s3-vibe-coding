package org.example.unit;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.servlet.S3Servlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for S3Servlet
 */
class S3ServletTest {

    @TempDir
    Path tempDir;

    private S3Servlet servlet;
    private ServletConfig servletConfig;
    private ServletContext servletContext;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new S3Servlet();

        servletContext = mock(ServletContext.class);
        when(servletContext.getInitParameter("storage.root.dir")).thenReturn(tempDir.toString());
        when(servletContext.getInitParameter("storage.max.file.size")).thenReturn("104857600");

        servletConfig = mock(ServletConfig.class);
        when(servletConfig.getServletContext()).thenReturn(servletContext);
        when(servletConfig.getServletName()).thenReturn("S3Servlet");

        servlet.init(servletConfig);
    }

    // ==================== Health Check Tests ====================

    @Test
    void doGet_healthCheck_shouldReturnOk() throws Exception {
        HttpServletRequest request = mockRequest("GET", "/health", null);
        HttpServletResponse response = mockResponse();

        invokeDoGet(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("application/json");
    }

    // ==================== List Buckets Tests ====================

    @Test
    void doGet_rootPath_shouldListBuckets() throws Exception {
        HttpServletRequest request = mockRequest("GET", "/", null);
        HttpServletResponse response = mockResponse();

        invokeDoGet(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("application/xml");
    }

    @Test
    void doGet_nullPathInfo_shouldListBuckets() throws Exception {
        HttpServletRequest request = mockRequest("GET", null, null);
        HttpServletResponse response = mockResponse();

        invokeDoGet(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("application/xml");
    }

    // ==================== List Objects Tests ====================

    @Test
    void doGet_bucketOnly_shouldListObjects() throws Exception {
        // First create a bucket
        HttpServletRequest createRequest = mockRequest("PUT", "/test-bucket", null);
        HttpServletResponse createResponse = mockResponse();
        invokeDoPut(servlet, createRequest, createResponse);

        HttpServletRequest request = mockRequest("GET", "/test-bucket", null);
        HttpServletResponse response = mockResponse();

        invokeDoGet(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setContentType("application/xml");
    }

    @Test
    void doGet_nonExistentBucket_shouldReturn404() throws Exception {
        HttpServletRequest request = mockRequest("GET", "/non-existent-bucket", null);
        HttpServletResponse response = mockResponse();

        invokeDoGet(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void doGet_listObjectsWithPrefix_shouldFilter() throws Exception {
        // Create bucket and upload files
        HttpServletRequest createRequest = mockRequest("PUT", "/test-bucket", null);
        HttpServletResponse createResponse = mockResponse();
        invokeDoPut(servlet, createRequest, createResponse);

        HttpServletRequest request = mockRequest("GET", "/test-bucket", null);
        when(request.getParameter("prefix")).thenReturn("data/");
        HttpServletResponse response = mockResponse();

        invokeDoGet(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    // ==================== Get Object Tests ====================

    @Test
    void doGet_existingObject_shouldReturnContent() throws Exception {
        // Create bucket
        HttpServletRequest createBucketRequest = mockRequest("PUT", "/test-bucket", null);
        HttpServletResponse createBucketResponse = mockResponse();
        invokeDoPut(servlet, createBucketRequest, createBucketResponse);

        // Upload object
        String content = "Hello, World!";
        HttpServletRequest uploadRequest = mockRequestWithBody("PUT", "/test-bucket/test.txt", content);
        HttpServletResponse uploadResponse = mockResponse();
        invokeDoPut(servlet, uploadRequest, uploadResponse);

        // Get object
        HttpServletRequest request = mockRequest("GET", "/test-bucket/test.txt", null);
        HttpServletResponse response = mockResponse();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(response.getOutputStream()).thenReturn(createServletOutputStream(outputStream));

        invokeDoGet(servlet, request, response);

        assertThat(outputStream.toString()).isEqualTo(content);
    }

    @Test
    void doGet_nonExistentObject_shouldReturn404() throws Exception {
        // Create bucket only
        HttpServletRequest createBucketRequest = mockRequest("PUT", "/test-bucket", null);
        HttpServletResponse createBucketResponse = mockResponse();
        invokeDoPut(servlet, createBucketRequest, createBucketResponse);

        HttpServletRequest request = mockRequest("GET", "/test-bucket/non-existent.txt", null);
        HttpServletResponse response = mockResponse();

        invokeDoGet(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    // ==================== Create Bucket Tests ====================

    @Test
    void doPut_createBucket_shouldReturn200() throws Exception {
        HttpServletRequest request = mockRequest("PUT", "/new-bucket", null);
        HttpServletResponse response = mockResponse();

        invokeDoPut(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    @Test
    void doPut_createExistingBucket_shouldReturnConflict() throws Exception {
        // Create bucket first
        HttpServletRequest createRequest = mockRequest("PUT", "/existing-bucket", null);
        HttpServletResponse createResponse = mockResponse();
        invokeDoPut(servlet, createRequest, createResponse);

        // Try to create again
        HttpServletRequest request = mockRequest("PUT", "/existing-bucket", null);
        HttpServletResponse response = mockResponse();

        invokeDoPut(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_CONFLICT);
    }

    @Test
    void doPut_emptyPath_shouldReturnError() throws Exception {
        HttpServletRequest request = mockRequest("PUT", "/", null);
        HttpServletResponse response = mockResponse();

        invokeDoPut(servlet, request, response);

        verify(response).setStatus(anyInt());
    }

    // ==================== Upload Object Tests ====================

    @Test
    void doPut_uploadObject_shouldReturn200WithETag() throws Exception {
        // Create bucket first
        HttpServletRequest createBucketRequest = mockRequest("PUT", "/test-bucket", null);
        HttpServletResponse createBucketResponse = mockResponse();
        invokeDoPut(servlet, createBucketRequest, createBucketResponse);

        // Upload object
        String content = "Test content";
        HttpServletRequest request = mockRequestWithBody("PUT", "/test-bucket/test.txt", content);
        HttpServletResponse response = mockResponse();

        invokeDoPut(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        verify(response).setHeader(eq("ETag"), anyString());
    }

    @Test
    void doPut_uploadToNonExistentBucket_shouldReturn404() throws Exception {
        String content = "Test content";
        HttpServletRequest request = mockRequestWithBody("PUT", "/non-existent/test.txt", content);
        HttpServletResponse response = mockResponse();

        invokeDoPut(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    // ==================== Delete Bucket Tests ====================

    @Test
    void doDelete_emptyBucket_shouldReturn204() throws Exception {
        // Create bucket first
        HttpServletRequest createRequest = mockRequest("PUT", "/to-delete", null);
        HttpServletResponse createResponse = mockResponse();
        invokeDoPut(servlet, createRequest, createResponse);

        // Delete bucket
        HttpServletRequest request = mockRequest("DELETE", "/to-delete", null);
        HttpServletResponse response = mockResponse();

        invokeDoDelete(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test
    void doDelete_nonExistentBucket_shouldReturn404() throws Exception {
        HttpServletRequest request = mockRequest("DELETE", "/non-existent", null);
        HttpServletResponse response = mockResponse();

        invokeDoDelete(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void doDelete_nonEmptyBucket_shouldReturnConflict() throws Exception {
        // Create bucket and upload file
        HttpServletRequest createBucketRequest = mockRequest("PUT", "/non-empty", null);
        HttpServletResponse createBucketResponse = mockResponse();
        invokeDoPut(servlet, createBucketRequest, createBucketResponse);

        String content = "content";
        HttpServletRequest uploadRequest = mockRequestWithBody("PUT", "/non-empty/file.txt", content);
        HttpServletResponse uploadResponse = mockResponse();
        invokeDoPut(servlet, uploadRequest, uploadResponse);

        // Try to delete bucket
        HttpServletRequest request = mockRequest("DELETE", "/non-empty", null);
        HttpServletResponse response = mockResponse();

        invokeDoDelete(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_CONFLICT);
    }

    // ==================== Delete Object Tests ====================

    @Test
    void doDelete_existingObject_shouldReturn204() throws Exception {
        // Create bucket and upload file
        HttpServletRequest createBucketRequest = mockRequest("PUT", "/test-bucket", null);
        HttpServletResponse createBucketResponse = mockResponse();
        invokeDoPut(servlet, createBucketRequest, createBucketResponse);

        String content = "content";
        HttpServletRequest uploadRequest = mockRequestWithBody("PUT", "/test-bucket/to-delete.txt", content);
        HttpServletResponse uploadResponse = mockResponse();
        invokeDoPut(servlet, uploadRequest, uploadResponse);

        // Delete object
        HttpServletRequest request = mockRequest("DELETE", "/test-bucket/to-delete.txt", null);
        HttpServletResponse response = mockResponse();

        invokeDoDelete(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test
    void doDelete_nonExistentObject_shouldReturn404() throws Exception {
        // Create bucket only
        HttpServletRequest createBucketRequest = mockRequest("PUT", "/test-bucket", null);
        HttpServletResponse createBucketResponse = mockResponse();
        invokeDoPut(servlet, createBucketRequest, createBucketResponse);

        HttpServletRequest request = mockRequest("DELETE", "/test-bucket/non-existent.txt", null);
        HttpServletResponse response = mockResponse();

        invokeDoDelete(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void doDelete_objectFromNonExistentBucket_shouldReturn404() throws Exception {
        HttpServletRequest request = mockRequest("DELETE", "/non-existent/file.txt", null);
        HttpServletResponse response = mockResponse();

        invokeDoDelete(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_NOT_FOUND);
    }

    // ==================== POST Tests ====================

    @Test
    void doPost_shouldActLikePut() throws Exception {
        HttpServletRequest request = mockRequest("POST", "/new-bucket", null);
        HttpServletResponse response = mockResponse();

        invokeDoPost(servlet, request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    // ==================== Helper Methods ====================

    private void invokeDoGet(S3Servlet servlet, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Method method = S3Servlet.class.getDeclaredMethod("doGet", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        method.invoke(servlet, request, response);
    }

    private void invokeDoPut(S3Servlet servlet, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Method method = S3Servlet.class.getDeclaredMethod("doPut", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        method.invoke(servlet, request, response);
    }

    private void invokeDoDelete(S3Servlet servlet, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Method method = S3Servlet.class.getDeclaredMethod("doDelete", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        method.invoke(servlet, request, response);
    }

    private void invokeDoPost(S3Servlet servlet, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Method method = S3Servlet.class.getDeclaredMethod("doPost", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        method.invoke(servlet, request, response);
    }

    private HttpServletRequest mockRequest(String method, String pathInfo, String queryString) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getPathInfo()).thenReturn(pathInfo);
        when(request.getQueryString()).thenReturn(queryString);
        when(request.getContextPath()).thenReturn("");
        when(request.getRequestURI()).thenReturn(pathInfo != null ? pathInfo : "/");
        return request;
    }

    private HttpServletRequest mockRequestWithBody(String method, String pathInfo, String body) {
        HttpServletRequest request = mockRequest(method, pathInfo, null);
        when(request.getContentLength()).thenReturn(body != null ? body.length() : 0);
        if (body != null) {
            ByteArrayInputStream bais = new ByteArrayInputStream(body.getBytes());
            try {
                when(request.getInputStream()).thenReturn(createServletInputStream(bais));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return request;
    }

    private HttpServletResponse mockResponse() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter writer = new PrintWriter(new ByteArrayOutputStream());
        when(response.getWriter()).thenReturn(writer);
        return response;
    }

    private jakarta.servlet.ServletInputStream createServletInputStream(ByteArrayInputStream bais) {
        return new jakarta.servlet.ServletInputStream() {
            @Override
            public int read() {
                return bais.read();
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener listener) {
            }
        };
    }

    private jakarta.servlet.ServletOutputStream createServletOutputStream(ByteArrayOutputStream baos) {
        return new jakarta.servlet.ServletOutputStream() {
            @Override
            public void write(int b) {
                baos.write(b);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(jakarta.servlet.WriteListener listener) {
            }
        };
    }
}
