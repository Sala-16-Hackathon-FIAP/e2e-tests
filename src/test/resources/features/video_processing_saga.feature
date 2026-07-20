Feature: Video Processing Saga
  As a FIAP-X user
  I want to upload a video and have it processed into frames
  So that I can download a ZIP with one frame per second

  Background:
    Given the user is authenticated

  Scenario: Happy path - upload a video and receive processed frames
    When the user initiates a multipart upload for "test-video.mov"
    Then the upload should be created with status "UPLOADING"
    When the user uploads chunk 1
    And the user completes the upload
    Then the upload status should be "COMPLETED"
    And the status-service should eventually show "UPLOAD_COMPLETED" for the upload
    And the status-service should eventually show "PROCESSING_COMPLETED" for the upload
    And the notification-service should have a "PROCESSING_COMPLETED" notification for the user
    And the result ZIP should exist in S3

  Scenario: Failed processing - user is notified of the error
    When the user initiates a multipart upload for "corrupted.mov"
    And the user uploads chunk 1
    And the user completes the upload
    And the status-service should eventually show "PROCESSING_FAILED" for the upload
    And the notification-service should have a "PROCESSING_FAILED" notification for the user
