package org.example.unit;

import org.example.filter.AuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthenticationFilter
 */
class AuthenticationFilterTest {

    private static final String TEST_TOKEN = "Bearer test-token-12345";
    private static final String TEST_HEADER = "Authorization";

    private AuthenticationFilter filter;
    private FilterConfig filterConfig;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter stringWriter;

    @BeforeEach
    void setUp() throws ServletException, IOException {
        filter = new AuthenticationFilter();

        filterConfig = mock(FilterConfig.class);
        when(filterConfig.getInitParameter("auth.enabled")).thenReturn("true");
        when(filterConfig.getInitParameter("auth.header")).thenReturn(TEST_HEADER);
        when(filterConfig.getInitParameter("auth.token")).thenReturn(TEST_TOKEN);

        filter.init(filterConfig);

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);

        stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        lenient().when(response.getWriter()).thenReturn(writer);
    }

    @AfterEach
    void tearDown() {
        filter.destroy();
    }

    @Test
    void doFilter_shouldPassThroughWhenAuthDisabled() throws Exception {
        // Given
        FilterConfig disabledConfig = mock(FilterConfig.class);
        when(disabledConfig.getInitParameter("auth.enabled")).thenReturn("false");
        when(disabledConfig.getInitParameter("auth.header")).thenReturn(TEST_HEADER);
        when(disabledConfig.getInitParameter("auth.token")).thenReturn(TEST_TOKEN);

        AuthenticationFilter disabledFilter = new AuthenticationFilter();
        disabledFilter.init(disabledConfig);

        // When
        disabledFilter.doFilter(request, response, chain);

        // Then
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void doFilter_shouldPassThroughForOptionsRequest() throws Exception {
        // Given
        when(request.getMethod()).thenReturn("OPTIONS");

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void doFilter_shouldReturn401WhenNoAuthHeaderProvided() throws Exception {
        // Given
        when(request.getHeader(TEST_HEADER)).thenReturn(null);

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_shouldReturn401WhenInvalidTokenProvided() throws Exception {
        // Given
        when(request.getHeader(TEST_HEADER)).thenReturn("Bearer wrong-token");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_shouldPassWhenValidTokenProvided() throws Exception {
        // Given
        when(request.getHeader(TEST_HEADER)).thenReturn(TEST_TOKEN);

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void init_shouldLoadConfiguration() throws ServletException {
        // When
        FilterConfig config = mock(FilterConfig.class);
        when(config.getInitParameter("auth.enabled")).thenReturn("true");
        when(config.getInitParameter("auth.header")).thenReturn("X-Custom-Auth");
        when(config.getInitParameter("auth.token")).thenReturn("custom-token");

        AuthenticationFilter newFilter = new AuthenticationFilter();
        newFilter.init(config);

        // Then - just verify it doesn't throw exception
        assertThat(newFilter).isNotNull();
    }
}
