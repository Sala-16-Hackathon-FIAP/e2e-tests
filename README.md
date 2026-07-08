# e2e-tests

End-to-end BDD tests for the FIAP-X video processing platform. Validates the complete choreographed saga flow — from video upload through frame extraction to user notification — using Cucumber and Gherkin scenarios.

## Technology Stack

- **Java 21** + **Maven**
- **Cucumber 7** + **JUnit 5** (BDD framework)
- **REST Assured** (HTTP API calls)
- **Awaitility** (async polling for eventual consistency)
- **RabbitMQ AMQP Client** (event publishing for failure scenarios)
- **AWS SDK v2** (S3 result verification via LocalStack)

## What It Tests

This project tests the **full saga flow** across all microservices:

```
[auth-service] login
       |
       v
[upload-service] initiate → chunk upload → complete
       |
       v (RabbitMQ: video.upload.completed)
       |
[video-processor-service] download → FFmpeg → upload ZIP
       |
       v (RabbitMQ: video.processing.completed / failed)
       |
       +---> [status-service] tracks all status transitions
       +---> [notification-service] notifies user of result
```

### BDD Scenarios

| Scenario | What it validates |
|---|---|
| Happy path | Upload → processing → COMPLETED status → notification → ZIP in S3 |
| Failed processing | Invalid S3 key → FAILED status → failure notification |

## Prerequisites

All services and infrastructure must be running:

- **auth-service** on port `8080`
- **upload-service** on port `8082`
- **video-processor-service** on port `8083`
- **status-service** on port `8084`
- **notification-service** on port `8085`
- **PostgreSQL** (each service on its own port)
- **RabbitMQ** on port `5672`
- **LocalStack** (S3) on port `4566`
- **FFmpeg** installed on the machine running the video-processor-service

### Quick Start with Docker Compose

From the root `modulo5/` directory:

```bash
docker-compose up -d
```

Then start each service (via Maven or IDE). See individual service READMEs for details.

## Running the Tests

### Default (all services on localhost)

```bash
cd e2e-tests
mvn test
```

### Custom service URLs

Override any URL via system properties:

```bash
mvn test \
  -Dauth.url=http://localhost:8080 \
  -Dupload.url=http://localhost:8082 \
  -Dprocessor.url=http://localhost:8083 \
  -Dstatus.url=http://localhost:8084 \
  -Dnotification.url=http://localhost:8085 \
  -Drabbitmq.host=localhost \
  -Ds3.endpoint=http://localhost:4566
```

### Run from IDE

Run `CucumberRunnerTest.java` directly using the Run/Debug button. Add system properties in the Run configuration if services are not on default ports.

## Test Reports

After running, reports are generated at:

- **HTML:** `target/cucumber-report.html`
- **JSON:** `target/cucumber-report.json`

Open the HTML report in a browser for a visual overview of all scenarios and steps.

## Project Structure

```
e2e-tests/
├── pom.xml
├── README.md
└── src/test/
    ├── java/br/com/fiapx/e2e/
    │   ├── CucumberRunnerTest.java      # Cucumber JUnit 5 runner
    │   ├── config/
    │   │   └── TestConfig.java          # URLs, credentials, S3 client
    │   └── steps/
    │       └── VideoProcessingSagaSteps.java  # Step definitions
    └── resources/
        ├── features/
        │   └── video_processing_saga.feature  # Gherkin scenarios
        └── logback-test.xml
```

## Configuration

All configuration is via system properties with sensible defaults:

| Property | Default | Description |
|---|---|---|
| `auth.url` | `http://localhost:8080` | auth-service base URL |
| `upload.url` | `http://localhost:8082` | upload-service base URL |
| `processor.url` | `http://localhost:8083` | video-processor-service base URL |
| `status.url` | `http://localhost:8084` | status-service base URL |
| `notification.url` | `http://localhost:8085` | notification-service base URL |
| `rabbitmq.host` | `localhost` | RabbitMQ host |
| `rabbitmq.port` | `5672` | RabbitMQ AMQP port |
| `rabbitmq.user` | `fiapx` | RabbitMQ user |
| `rabbitmq.pass` | `fiapx123` | RabbitMQ password |
| `s3.endpoint` | `http://localhost:4566` | S3/LocalStack endpoint |
| `s3.bucket` | `fiapx-videos` | S3 bucket name |
| `auth.email` | `useradmin@email.com` | Test user email |
| `auth.password` | `Admin@12345` | Test user password |
| `poll.timeout` | `30` | Max seconds to wait for async results |

## CI/CD

These tests run as a post-deploy validation step. In CI, they execute after all microservices are deployed to the target environment. They are **not** deployed as a service — this project has no Dockerfile or Kubernetes manifests.
