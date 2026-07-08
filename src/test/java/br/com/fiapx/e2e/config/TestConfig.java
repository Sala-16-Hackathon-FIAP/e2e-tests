package br.com.fiapx.e2e.config;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

public final class TestConfig {

    private TestConfig() {}

    public static final String AUTH_URL = System.getProperty("auth.url", "http://localhost:8080");
    public static final String UPLOAD_URL = System.getProperty("upload.url", "http://localhost:8082");
    public static final String PROCESSOR_URL = System.getProperty("processor.url", "http://localhost:8083");
    public static final String STATUS_URL = System.getProperty("status.url", "http://localhost:8084");
    public static final String NOTIFICATION_URL = System.getProperty("notification.url", "http://localhost:8085");

    public static final String RABBITMQ_HOST = System.getProperty("rabbitmq.host", "localhost");
    public static final int RABBITMQ_PORT = Integer.parseInt(System.getProperty("rabbitmq.port", "5672"));
    public static final String RABBITMQ_USER = System.getProperty("rabbitmq.user", "fiapx");
    public static final String RABBITMQ_PASS = System.getProperty("rabbitmq.pass", "fiapx123");

    public static final String S3_ENDPOINT = System.getProperty("s3.endpoint", "http://localhost:4566");
    public static final String S3_BUCKET = System.getProperty("s3.bucket", "fiapx-videos");

    public static final String AUTH_EMAIL = System.getProperty("auth.email", "useradmin@email.com");
    public static final String AUTH_PASSWORD = System.getProperty("auth.password", "Admin@12345");

    public static final int POLL_TIMEOUT_SECONDS = Integer.parseInt(System.getProperty("poll.timeout", "30"));

    public static S3Client createS3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(S3_ENDPOINT))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .checksumValidationEnabled(false)
                        .build())
                .forcePathStyle(true)
                .build();
    }
}
