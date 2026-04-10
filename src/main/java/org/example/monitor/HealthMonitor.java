package org.example.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 健康监控服务
 * 定期检测服务健康状态
 */
public class HealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);

    private final String healthUrl;
    private final long intervalSeconds;
    private final HttpClient httpClient;
    private ScheduledExecutorService scheduler;

    public HealthMonitor(String baseUrl, long intervalSeconds) {
        this.healthUrl = baseUrl + "/api/health";
        this.intervalSeconds = intervalSeconds;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 启动健康监控
     */
    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            log.warn("HealthMonitor is already running");
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-monitor");
            t.setDaemon(true);
            return t;
        });

        // 初始延迟 5 秒，然后每隔 intervalSeconds 秒执行一次
        scheduler.scheduleAtFixedRate(
                this::checkHealth,
                5,
                intervalSeconds,
                TimeUnit.SECONDS
        );

        log.info("HealthMonitor started, checking {} every {} seconds", healthUrl, intervalSeconds);
    }

    /**
     * 停止健康监控
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("HealthMonitor stopped");
        }
    }

    /**
     * 执行健康检查
     */
    private void checkHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.debug("Health check passed: {}", response.body());
            } else {
                log.error("Health check failed: status={}, body={}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Health check error: unable to connect to {}", healthUrl, e);
        }
    }
}
