package org.example.unit;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.servlet.OriginConfigServlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OriginConfigServletTest {

    @TempDir
    Path tempDir;

    private OriginConfigServlet servlet;

    @BeforeEach
    void setUp() throws Exception {
        servlet = new OriginConfigServlet();

        ServletContext servletContext = mock(ServletContext.class);
        when(servletContext.getInitParameter("storage.root.dir")).thenReturn(tempDir.toString());

        ServletConfig servletConfig = mock(ServletConfig.class);
        when(servletConfig.getServletContext()).thenReturn(servletContext);
        when(servletConfig.getServletName()).thenReturn("OriginConfigServlet");

        servlet.init(servletConfig);
    }

    // ==================== doGet Tests ====================

    @Test
    void doGet_returns404WhenNotConfigured() throws Exception {
        createBucket("test-bucket");

        HttpServletRequest request = mockRequest("/test-bucket");
        StringWriter sw = new StringWriter();
        HttpServletResponse response = mockResponseWithStringWriter(sw);

        invokeDoGet(servlet, request, response);

        verify(response).setStatus(404);
    }

    @Test
    void doGet_returnsConfigAsJsonWhenConfigured() throws Exception {
        createBucket("my-bucket");

        // First save a config via doPut
        String configJson = "{\"originUrl\":\"https://origin.example.com\",\"originBucket\":\"src-bucket\",\"prefix\":\"data/\",\"cachePolicy\":\"no-cache\"}";
        HttpServletRequest putRequest = mockRequestWithBody("/my-bucket", configJson);
        StringWriter putSw = new StringWriter();
        HttpServletResponse putResponse = mockResponseWithStringWriter(putSw);

        invokeDoPut(servlet, putRequest, putResponse);

        // Now read it back via doGet
        HttpServletRequest getRequest = mockRequest("/my-bucket");
        StringWriter getSw = new StringWriter();
        HttpServletResponse getResponse = mockResponseWithStringWriter(getSw);

        invokeDoGet(servlet, getRequest, getResponse);

        verify(getResponse).setStatus(200);
        verify(getResponse).setContentType("application/json");
        String responseBody = getSw.toString();
        assertThat(responseBody).contains("\"originUrl\":\"https://origin.example.com\"");
        assertThat(responseBody).contains("\"originBucket\":\"src-bucket\"");
        assertThat(responseBody).contains("\"prefix\":\"data/\"");
        assertThat(responseBody).contains("\"cachePolicy\":\"no-cache\"");
    }

    // ==================== doPut Tests ====================

    @Test
    void doPut_savesConfigAndCanBeReadBack() throws Exception {
        createBucket("save-bucket");

        String configJson = "{\"originUrl\":\"https://upstream.example.com\",\"originBucket\":\"up-bucket\",\"cachePolicy\":\"cache\"}";
        HttpServletRequest request = mockRequestWithBody("/save-bucket", configJson);
        StringWriter sw = new StringWriter();
        HttpServletResponse response = mockResponseWithStringWriter(sw);

        invokeDoPut(servlet, request, response);

        verify(response).setStatus(200);
        verify(response).setContentType("application/json");

        // Verify config file was written on disk
        Path configFile = tempDir.resolve("save-bucket").resolve(".origin-config.json");
        assertThat(Files.exists(configFile)).isTrue();
        String saved = Files.readString(configFile);
        assertThat(saved).contains("\"originUrl\":\"https://upstream.example.com\"");
        assertThat(saved).contains("\"originBucket\":\"up-bucket\"");
        assertThat(saved).contains("\"cachePolicy\":\"cache\"");
    }

    // ==================== doDelete Tests ====================

    @Test
    void doDelete_removesConfig() throws Exception {
        createBucket("del-bucket");

        // First save a config
        String configJson = "{\"originUrl\":\"https://origin.example.com\",\"originBucket\":\"src\",\"cachePolicy\":\"no-cache\"}";
        HttpServletRequest putRequest = mockRequestWithBody("/del-bucket", configJson);
        StringWriter putSw = new StringWriter();
        HttpServletResponse putResponse = mockResponseWithStringWriter(putSw);
        invokeDoPut(servlet, putRequest, putResponse);

        // Verify it exists
        Path configFile = tempDir.resolve("del-bucket").resolve(".origin-config.json");
        assertThat(Files.exists(configFile)).isTrue();

        // Delete it
        HttpServletRequest deleteRequest = mockRequest("/del-bucket");
        HttpServletResponse deleteResponse = mock(HttpServletResponse.class);
        invokeDoDelete(servlet, deleteRequest, deleteResponse);

        verify(deleteResponse).setStatus(204);

        // Verify it's gone
        assertThat(Files.exists(configFile)).isFalse();

        // Verify doGet now returns 404
        HttpServletRequest getRequest = mockRequest("/del-bucket");
        StringWriter getSw = new StringWriter();
        HttpServletResponse getResponse = mockResponseWithStringWriter(getSw);
        invokeDoGet(servlet, getRequest, getResponse);
        verify(getResponse).setStatus(404);
    }

    // ==================== Helper Methods ====================

    private void createBucket(String name) throws Exception {
        Path bucketDir = tempDir.resolve(name);
        Files.createDirectories(bucketDir);
        Files.writeString(bucketDir.resolve(".bucket-created"), String.valueOf(System.currentTimeMillis()));
    }

    private HttpServletRequest mockRequest(String pathInfo) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getPathInfo()).thenReturn(pathInfo);
        return request;
    }

    private HttpServletRequest mockRequestWithBody(String pathInfo, String body) throws Exception {
        HttpServletRequest request = mockRequest(pathInfo);
        when(request.getReader()).thenReturn(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)));
        return request;
    }

    private HttpServletResponse mockResponseWithStringWriter(StringWriter sw) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(sw));
        return response;
    }

    private void invokeDoGet(OriginConfigServlet servlet, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Method method = OriginConfigServlet.class.getDeclaredMethod("doGet", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        method.invoke(servlet, request, response);
    }

    private void invokeDoPut(OriginConfigServlet servlet, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Method method = OriginConfigServlet.class.getDeclaredMethod("doPut", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        method.invoke(servlet, request, response);
    }

    private void invokeDoDelete(OriginConfigServlet servlet, HttpServletRequest request, HttpServletResponse response) throws Exception {
        Method method = OriginConfigServlet.class.getDeclaredMethod("doDelete", HttpServletRequest.class, HttpServletResponse.class);
        method.setAccessible(true);
        method.invoke(servlet, request, response);
    }
}
