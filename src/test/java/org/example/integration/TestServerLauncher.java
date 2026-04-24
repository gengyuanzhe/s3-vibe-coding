package org.example.integration;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Launcher for starting a Jetty S3 server in a separate JVM process.
 * Used by process-isolated integration tests.
 *
 * Usage: java -cp &lt;classpath&gt; org.example.integration.TestServerLauncher &lt;port&gt; &lt;storageDir&gt; &lt;webappPath&gt;
 * Prints "READY:&lt;actualPort&gt;" to stdout when server is ready.
 */
public class TestServerLauncher {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String storageDir = args[1];
        String webappPath = args[2];

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        // Write override web.xml with test configuration
        Path overrideDir = Files.createTempDirectory("s3-test-override");
        String overrideXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\">\n"
                + "    <context-param>\n"
                + "        <param-name>storage.root.dir</param-name>\n"
                + "        <param-value>" + storageDir + "</param-value>\n"
                + "    </context-param>\n"
                + "    <context-param>\n"
                + "        <param-name>storage.max.file.size</param-name>\n"
                + "        <param-value>104857600</param-value>\n"
                + "    </context-param>\n"
                + "    <context-param>\n"
                + "        <param-name>health.monitor.enabled</param-name>\n"
                + "        <param-value>false</param-value>\n"
                + "    </context-param>\n"
                + "    <filter>\n"
                + "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n"
                + "        <filter-class>org.example.filter.AwsV4AuthenticationFilter</filter-class>\n"
                + "        <init-param><param-name>auth.mode</param-name><param-value>both</param-value></init-param>\n"
                + "        <init-param><param-name>auth.region</param-name><param-value>us-east-1</param-value></init-param>\n"
                + "        <init-param><param-name>auth.service</param-name><param-value>s3</param-value></init-param>\n"
                + "        <init-param><param-name>auth.time.skew.minutes</param-name><param-value>15</param-value></init-param>\n"
                + "    </filter>\n"
                + "    <filter-mapping>\n"
                + "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n"
                + "        <url-pattern>/*</url-pattern>\n"
                + "    </filter-mapping>\n"
                + "    <servlet>\n"
                + "        <servlet-name>AuthAdminServlet</servlet-name>\n"
                + "        <servlet-class>org.example.servlet.AuthAdminServlet</servlet-class>\n"
                + "    </servlet>\n"
                + "    <servlet-mapping>\n"
                + "        <servlet-name>AuthAdminServlet</servlet-name>\n"
                + "        <url-pattern>/admin/*</url-pattern>\n"
                + "    </servlet-mapping>\n"
                + "    <servlet>\n"
                + "        <servlet-name>OriginConfigServlet</servlet-name>\n"
                + "        <servlet-class>org.example.servlet.OriginConfigServlet</servlet-class>\n"
                + "    </servlet>\n"
                + "    <servlet-mapping>\n"
                + "        <servlet-name>OriginConfigServlet</servlet-name>\n"
                + "        <url-pattern>/admin/origin-config/*</url-pattern>\n"
                + "    </servlet-mapping>\n"
                + "    <servlet>\n"
                + "        <servlet-name>S3Servlet</servlet-name>\n"
                + "        <servlet-class>org.example.servlet.S3Servlet</servlet-class>\n"
                + "        <load-on-startup>1</load-on-startup>\n"
                + "    </servlet>\n"
                + "    <servlet-mapping>\n"
                + "        <servlet-name>S3Servlet</servlet-name>\n"
                + "        <url-pattern>/*</url-pattern>\n"
                + "    </servlet-mapping>\n"
                + "</web-app>";

        Path overrideXmlPath = overrideDir.resolve("override-web.xml");
        Files.writeString(overrideXmlPath, overrideXml);

        WebAppContext ctx = new WebAppContext();
        ctx.setContextPath("/");
        ctx.setWar(webappPath);
        ctx.setExtractWAR(false);
        ctx.setOverrideDescriptor(overrideXmlPath.toFile().getAbsolutePath());

        server.setHandler(ctx);
        server.start();

        int actualPort = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        System.out.println("READY:" + actualPort);
        System.out.flush();

        // Block until killed
        new CountDownLatch(1).await();
    }
}
