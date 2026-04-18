package org.example.unit;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.auth.AuthState;
import org.example.servlet.AuthAdminServlet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

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
        when(request.getReader()).thenReturn(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(jsonBody.getBytes()), StandardCharsets.UTF_8)));

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
        when(request.getReader()).thenReturn(new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(jsonBody.getBytes()), StandardCharsets.UTF_8)));

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
