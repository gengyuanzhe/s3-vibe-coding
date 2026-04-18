package org.example.integration;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AwsSdkIntegrationTest {

    private static final String TEST_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String TEST_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String TEST_BUCKET = "sdk-test-bucket";

    private static Server server;
    private static int port;
    private static S3Client s3Client;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startServer() throws Exception {
        Path testStorageDir = tempDir.resolve("storage");
        Files.createDirectories(testStorageDir);

        Path credentialsFile = tempDir.resolve("credentials.properties");
        Files.writeString(credentialsFile,
                "accessKey.default=" + TEST_ACCESS_KEY + "\n" +
                "secretKey.default=" + TEST_SECRET_KEY + "\n");

        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        String webappPath = new File("src/main/webapp").getAbsolutePath();
        WebAppContext webAppContext = new WebAppContext();
        webAppContext.setContextPath("/");
        webAppContext.setWar(webappPath);
        webAppContext.setExtractWAR(false);

        String overrideWebXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\"\n" +
                "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "         xsi:schemaLocation=\"https://jakarta.ee/xml/ns/jakartaee\n" +
                "         https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd\"\n" +
                "         version=\"6.0\">\n" +
                "    <context-param>\n" +
                "        <param-name>storage.root.dir</param-name>\n" +
                "        <param-value>" + testStorageDir.toAbsolutePath() + "</param-value>\n" +
                "    </context-param>\n" +
                "    <context-param>\n" +
                "        <param-name>storage.max.file.size</param-name>\n" +
                "        <param-value>104857600</param-value>\n" +
                "    </context-param>\n" +
                "    <context-param>\n" +
                "        <param-name>health.monitor.enabled</param-name>\n" +
                "        <param-value>false</param-value>\n" +
                "    </context-param>\n" +
                "    <filter>\n" +
                "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n" +
                "        <filter-class>org.example.filter.AwsV4AuthenticationFilter</filter-class>\n" +
                "        <init-param>\n" +
                "            <param-name>auth.mode</param-name>\n" +
                "            <param-value>aws-v4</param-value>\n" +
                "        </init-param>\n" +
                "        <init-param>\n" +
                "            <param-name>auth.region</param-name>\n" +
                "            <param-value>us-east-1</param-value>\n" +
                "        </init-param>\n" +
                "        <init-param>\n" +
                "            <param-name>auth.service</param-name>\n" +
                "            <param-value>s3</param-value>\n" +
                "        </init-param>\n" +
                "        <init-param>\n" +
                "            <param-name>credentials.file.path</param-name>\n" +
                "            <param-value>" + credentialsFile.toAbsolutePath() + "</param-value>\n" +
                "        </init-param>\n" +
                "    </filter>\n" +
                "    <filter-mapping>\n" +
                "        <filter-name>AwsV4AuthenticationFilter</filter-name>\n" +
                "        <url-pattern>/*</url-pattern>\n" +
                "    </filter-mapping>\n" +
                "</web-app>";

        Path overrideWebXmlPath = tempDir.resolve("sdk-override-web.xml");
        Files.writeString(overrideWebXmlPath, overrideWebXml);
        webAppContext.setOverrideDescriptor(overrideWebXmlPath.toFile().getAbsolutePath());

        server.setHandler(webAppContext);
        server.start();

        port = connector.getLocalPort();

        s3Client = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(TEST_ACCESS_KEY, TEST_SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .checksumValidationEnabled(false)
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();

        System.out.println("========================================");
        System.out.println("AWS SDK Integration Test Server Started");
        System.out.println("Port: " + port);
        System.out.println("Endpoint: http://localhost:" + port + "/api");
        System.out.println("========================================");
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (s3Client != null) s3Client.close();
        if (server != null) server.stop();
    }

    @Test
    @Order(1)
    void createBucket_shouldSucceed() {
        CreateBucketRequest request = CreateBucketRequest.builder()
                .bucket(TEST_BUCKET)
                .build();
        s3Client.createBucket(request);
    }

    @Test
    @Order(2)
    void listBuckets_shouldContainCreatedBucket() {
        ListBucketsResponse response = s3Client.listBuckets();
        assertThat(response.buckets())
                .anyMatch(b -> b.name().equals(TEST_BUCKET));
    }

    @Test
    @Order(3)
    void putObject_shouldSucceed() {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(TEST_BUCKET)
                .key("hello.txt")
                .build();
        PutObjectResponse response = s3Client.putObject(request, RequestBody.fromString("Hello, S3!"));
        assertThat(response.eTag()).isNotNull();
    }

    @Test
    @Order(4)
    void getObject_shouldReturnContent() {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(TEST_BUCKET)
                .key("hello.txt")
                .build();
        byte[] content = s3Client.getObjectAsBytes(request).asByteArray();
        assertThat(new String(content)).isEqualTo("Hello, S3!");
    }

    @Test
    @Order(5)
    void listObjectsV2_shouldContainObject() {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(TEST_BUCKET)
                .build();
        ListObjectsV2Response response = s3Client.listObjectsV2(request);
        assertThat(response.contents()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(response.contents().stream().map(S3Object::key))
                .contains("hello.txt");
    }

    @Test
    @Order(6)
    void deleteObject_shouldSucceed() {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(TEST_BUCKET)
                .key("hello.txt")
                .build();
        s3Client.deleteObject(request);
    }

    @Test
    @Order(7)
    void deleteBucket_shouldSucceed() {
        DeleteBucketRequest request = DeleteBucketRequest.builder()
                .bucket(TEST_BUCKET)
                .build();
        s3Client.deleteBucket(request);
    }
}
