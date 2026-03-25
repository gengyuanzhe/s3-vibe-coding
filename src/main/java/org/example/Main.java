package org.example;

import java.io.File;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class to start the Jetty server
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.warn("Invalid port number provided, using default port 8080");
            }
        }

        Server server = createServer(port);

        try {
            server.start();
            logger.info("========================================");
            logger.info("S3-Like Storage Service Started");
            logger.info("Server listening on port: {}", port);
            logger.info("Health check: http://localhost:{}/api/health", port);
            logger.info("========================================");
            logger.info("Available endpoints:");
            logger.info("  GET    /                    - Web management UI");
            logger.info("  GET    /api/                - List all buckets");
            logger.info("  GET    /api/{bucket}        - List objects in bucket");
            logger.info("  GET    /api/{bucket}/{key}   - Download object");
            logger.info("  PUT    /api/{bucket}        - Create bucket");
            logger.info("  PUT    /api/{bucket}/{key}   - Upload object");
            logger.info("  DELETE /api/{bucket}        - Delete bucket");
            logger.info("  DELETE /api/{bucket}/{key}   - Delete object");
            logger.info("========================================");
            logger.info("Example commands:");
            logger.info("  # Create a bucket");
            logger.info("  curl -X PUT http://localhost:{}/api/my-bucket", port);
            logger.info("");
            logger.info("  # Upload a file");
            logger.info("  curl -X PUT --data-binary @myfile.txt http://localhost:{}/api/my-bucket/myfile.txt", port);
            logger.info("");
            logger.info("  # Download a file");
            logger.info("  curl -O http://localhost:{}/api/my-bucket/myfile.txt", port);
            logger.info("  # List objects in bucket");
            logger.info("  curl http://localhost:{}/api/my-bucket", port);
            logger.info("  # Delete a file");
            logger.info("  curl -X DELETE http://localhost:{}/api/my-bucket/myfile.txt", port);
            logger.info("========================================");

            server.join();
        } catch (Exception e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        } finally {
            if (server.isStopped()) {
                logger.info("Server stopped");
            }
        }
    }

    /**
     * Create and configure the Jetty server
     */
    private static Server createServer(int port) {
        // Configure thread pool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(200);
        threadPool.setMinThreads(8);
        threadPool.setIdleTimeout(60000);

        Server server = new Server(threadPool);

        // Create connector for HTTP
        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory();
        ServerConnector connector = new ServerConnector(server, httpConnectionFactory);
        connector.setPort(port);
        server.addConnector(connector);

        // Create web application context
        // Use absolute path to webapp directory
        String webappPath = new File("src/main/webapp").getAbsolutePath();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWar(webappPath);
        webAppContext.setExtractWAR(false);

        // Set handler directly (StatisticsHandler wrapping requires additional dependencies)
        server.setHandler(webAppContext);

        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping server...");
            try {
                server.stop();
                logger.info("Server stopped gracefully");
            } catch (Exception e) {
                logger.error("Error during server shutdown", e);
            }
        }));

        return server;
    }
}