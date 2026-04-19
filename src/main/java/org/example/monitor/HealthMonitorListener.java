package org.example.monitor;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServletContextListener 用于启动和停止健康监控
 */
@WebListener
public class HealthMonitorListener implements ServletContextListener {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitorListener.class);

    private static final String PARAM_HEALTH_ENABLED = "health.monitor.enabled";
    private static final String PARAM_HEALTH_INTERVAL = "health.monitor.interval.seconds";
    private static final String PARAM_HEALTH_BASE_URL = "health.monitor.base.url";

    private static final boolean DEFAULT_ENABLED = true;
    private static final long DEFAULT_INTERVAL = 10;
    private static final String DEFAULT_BASE_URL = "http://localhost:5080";

    private HealthMonitor healthMonitor;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext context = sce.getServletContext();

        boolean enabled = getBooleanParam(context, PARAM_HEALTH_ENABLED, DEFAULT_ENABLED);
        if (!enabled) {
            log.info("HealthMonitor is disabled");
            return;
        }

        long interval = getLongParam(context, PARAM_HEALTH_INTERVAL, DEFAULT_INTERVAL);
        String baseUrl = getStringParam(context, PARAM_HEALTH_BASE_URL, DEFAULT_BASE_URL);

        // 尝试从服务器端口动态构建 URL
        String serverPort = System.getProperty("server.port");
        if (serverPort != null && !serverPort.isBlank()) {
            baseUrl = "http://localhost:" + serverPort;
        }

        healthMonitor = new HealthMonitor(baseUrl, interval);
        healthMonitor.start();

        log.info("HealthMonitorListener initialized with baseUrl={}, interval={}s", baseUrl, interval);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (healthMonitor != null) {
            healthMonitor.stop();
            healthMonitor = null;
        }
        log.info("HealthMonitorListener destroyed");
    }

    private boolean getBooleanParam(ServletContext context, String name, boolean defaultValue) {
        String value = context.getInitParameter(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private long getLongParam(ServletContext context, String name, long defaultValue) {
        String value = context.getInitParameter(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid parameter {}: {}, using default {}", name, value, defaultValue);
            return defaultValue;
        }
    }

    private String getStringParam(ServletContext context, String name, String defaultValue) {
        String value = context.getInitParameter(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
