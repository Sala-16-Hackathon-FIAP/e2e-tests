package br.com.fiapx.e2e.steps;

import br.com.fiapx.e2e.config.TestConfig;
import io.cucumber.java.Before;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

public class VideoProcessingSagaSteps {

    private static final List<String> STATUS_PROGRESSION = List.of(
            "UPLOAD_COMPLETED", "PROCESSING_STARTED", "PROCESSING_COMPLETED"
    );
    private static final List<String> FAILURE_PROGRESSION = List.of(
            "UPLOAD_COMPLETED", "PROCESSING_STARTED", "PROCESSING_FAILED"
    );

    private String token;
    private String userId;
    private UUID uploadId;
    private String s3Key;
    private byte[] videoChunk;
    private final byte[] fakeChunk = new byte[1024];
    private boolean currentFilenameIsCorrupted;

    @Before
    public void setup() throws IOException {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        videoChunk = getClass().getResourceAsStream("/fixtures/test-video.mov").readAllBytes();
    }

    @Given("the user is authenticated")
    public void theUserIsAuthenticated() {
        Response response = given()
                .baseUri(TestConfig.AUTH_URL)
                .contentType(ContentType.JSON)
                .body("""
                    {"email":"%s","password":"%s"}
                    """.formatted(TestConfig.AUTH_EMAIL, TestConfig.AUTH_PASSWORD))
                .when()
                .post("/api/v1/auth/login");

        assertEquals(200, response.statusCode(), "Auth login failed");
        token = response.jsonPath().getString("bearerToken");
        assertNotNull(token, "Token is null");

        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(
                padBase64(parts[1])));
        try {
            com.fasterxml.jackson.databind.JsonNode tree = com.fasterxml.jackson.databind.json.JsonMapper
                    .builder().build().readTree(payload);
            userId = tree.get("sub").asText();
        } catch (Exception e) {
            fail("Failed to parse JWT payload: " + e.getMessage());
        }
        assertNotNull(userId, "userId is null");
    }

    @When("the user initiates a multipart upload for {string}")
    public void theUserInitiatesUpload(String filename) {
        currentFilenameIsCorrupted = filename.contains("corrupted");
        long fileSize = currentFilenameIsCorrupted ? 1024 : videoChunk.length;
        Response response = given()
                .baseUri(TestConfig.UPLOAD_URL)
                .header("Authorization", "Bearer " + token)
                .contentType(ContentType.JSON)
                .body("""
                    {"filename":"%s","fileSize":%d,"mimeType":"video/quicktime"}
                    """.formatted(filename, fileSize))
                .when()
                .post("/api/v1/uploads/initiate");

        assertEquals(201, response.statusCode(), "Initiate upload failed: " + response.body().asString());
        uploadId = UUID.fromString(response.jsonPath().getString("id"));
    }

    @Then("the upload should be created with status {string}")
    public void theUploadShouldHaveStatus(String expectedStatus) {
        Response response = given()
                .baseUri(TestConfig.UPLOAD_URL)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/uploads/" + uploadId);

        assertEquals(200, response.statusCode());
        assertEquals(expectedStatus, response.jsonPath().getString("status"));
    }

    @When("the user uploads chunk {int}")
    public void theUserUploadsChunk(int chunkNumber) {
        byte[] data = currentFilenameIsCorrupted ? fakeChunk : videoChunk;
        Response response = given()
                .baseUri(TestConfig.UPLOAD_URL)
                .header("Authorization", "Bearer " + token)
                .multiPart("file", "chunk-" + chunkNumber + ".bin", data)
                .when()
                .put("/api/v1/uploads/" + uploadId + "/chunks/" + chunkNumber);

        assertEquals(200, response.statusCode(), "Chunk upload failed: " + response.body().asString());
    }

    @And("the user completes the upload")
    public void theUserCompletesTheUpload() {
        Response response = given()
                .baseUri(TestConfig.UPLOAD_URL)
                .header("Authorization", "Bearer " + token)
                .when()
                .post("/api/v1/uploads/" + uploadId + "/complete");

        assertEquals(200, response.statusCode(), "Complete upload failed: " + response.body().asString());
        s3Key = "uploads/" + userId + "/" + uploadId + "/test-video.mov";
    }

    @Then("the upload status should be {string}")
    public void theUploadStatusShouldBe(String expectedStatus) {
        Response response = given()
                .baseUri(TestConfig.UPLOAD_URL)
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/uploads/" + uploadId);

        assertEquals(200, response.statusCode());
        assertEquals(expectedStatus, response.jsonPath().getString("status"));
    }

    @And("the status-service should eventually show {string} for the upload")
    public void statusServiceShouldShow(String expectedStatus) {
        await().atMost(TestConfig.POLL_TIMEOUT_SECONDS, SECONDS)
                .pollInterval(2, SECONDS)
                .untilAsserted(() -> {
                    Response response = given()
                            .baseUri(TestConfig.STATUS_URL)
                            .header("Authorization", "Bearer " + token)
                            .when()
                            .get("/api/v1/status/uploads/" + uploadId);

                    assertEquals(200, response.statusCode());
                    String actualStatus = response.jsonPath().getString("status");
                    assertTrue(
                            statusAtOrPast(actualStatus, expectedStatus),
                            "Expected status at or past " + expectedStatus + " but was " + actualStatus
                    );
                });
    }

    private boolean statusAtOrPast(String actual, String expected) {
        List<String> progression = expected.contains("FAILED") || actual.contains("FAILED")
                ? FAILURE_PROGRESSION : STATUS_PROGRESSION;
        int actualIdx = progression.indexOf(actual);
        int expectedIdx = progression.indexOf(expected);
        if (actualIdx == -1 || expectedIdx == -1) {
            return actual.equals(expected);
        }
        return actualIdx >= expectedIdx;
    }

    @And("the notification-service should have a {string} notification for the user")
    public void notificationServiceShouldHave(String expectedType) {
        await().atMost(TestConfig.POLL_TIMEOUT_SECONDS, SECONDS)
                .pollInterval(2, SECONDS)
                .untilAsserted(() -> {
                    Response response = given()
                            .baseUri(TestConfig.NOTIFICATION_URL)
                            .header("Authorization", "Bearer " + token)
                            .when()
                            .get("/api/v1/notifications");

                    assertEquals(200, response.statusCode());
                    List<?> notifications = response.jsonPath().getList("$");
                    assertFalse(notifications.isEmpty(), "No notifications found");
                    boolean found = response.jsonPath().getList("type")
                            .stream().anyMatch(t -> expectedType.equals(t));
                    assertTrue(found, "No notification of type " + expectedType +
                            ", found: " + response.jsonPath().getList("type"));
                });
    }

    @And("the result ZIP should exist in S3")
    public void resultZipShouldExistInS3() {
        try (S3Client s3 = TestConfig.createS3Client()) {
            await().atMost(TestConfig.POLL_TIMEOUT_SECONDS, SECONDS)
                    .pollInterval(2, SECONDS)
                    .untilAsserted(() -> {
                        List<S3Object> objects = s3.listObjectsV2(ListObjectsV2Request.builder()
                                .bucket(TestConfig.S3_BUCKET)
                                .prefix("processed/" + userId + "/")
                                .build()).contents();
                        boolean hasZip = objects.stream()
                                .anyMatch(o -> o.key().endsWith("frames.zip"));
                        assertTrue(hasZip, "No frames.zip found in S3 under processed/" + userId + "/");
                    });
        }
    }

    @And("a processing event is published with an invalid s3Key")
    public void publishEventWithInvalidS3Key() {
        s3Key = "uploads/nonexistent/invalid-key.mov";
        try (var connection = new com.rabbitmq.client.ConnectionFactory() {{
            setHost(TestConfig.RABBITMQ_HOST);
            setPort(TestConfig.RABBITMQ_PORT);
            setUsername(TestConfig.RABBITMQ_USER);
            setPassword(TestConfig.RABBITMQ_PASS);
        }}.newConnection(); var channel = connection.createChannel()) {
            String payload = """
                    {"uploadId":"%s","userId":"%s","filename":"corrupted.mov",
                     "s3Key":"%s","mimeType":"video/quicktime","uploadedAt":"2026-07-06T12:00:00"}
                    """.formatted(uploadId, userId, s3Key);
            channel.basicPublish("fiapx.events", "video.upload.completed",
                    new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                            .contentType("application/json").build(),
                    payload.getBytes());
        } catch (Exception e) {
            fail("Failed to publish RabbitMQ event: " + e.getMessage());
        }
    }

    private static String padBase64(String base64) {
        int padding = 4 - (base64.length() % 4);
        if (padding < 4) base64 += "=".repeat(padding);
        return base64;
    }
}
