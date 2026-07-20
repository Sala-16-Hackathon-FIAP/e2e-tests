# e2e-tests

End-to-end BDD tests for the FIAP-X video processing platform. Validates the complete choreographed saga flow — from video upload through frame extraction to user notification — using Cucumber and Gherkin scenarios.

## Technology Stack

- **Java 21** + **Maven**
- **Cucumber 7** + **JUnit 5** (BDD framework)
- **REST Assured** (HTTP API calls)
- **Awaitility** (async polling for eventual consistency)
- **RabbitMQ AMQP Client** (event publishing for failure scenarios)
- **AWS SDK v2** (S3 result verification)

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
| Happy path | Upload (UPLOADING → COMPLETED) → processing → PROCESSING_COMPLETED status → notification → ZIP in S3 |
| Failed processing | Invalid S3 key → PROCESSING_FAILED status → failure notification |

## Prerequisites

All services and infrastructure must be running:

- **auth-service**, **upload-service**, **video-processor-service**, **status-service**, **notification-service**
- **PostgreSQL** (each service with its own database)
- **RabbitMQ** on port `5672`
- **S3** (AWS S3 or LocalStack for local development)
- **FFmpeg** installed on the machine running the video-processor-service

A default admin user (`useradmin@email.com` / `Admin@12345`) is automatically created by the auth-service on startup via `DataInitializer`.

## Running the Tests

### Local (all services on localhost with LocalStack)

```bash
mvn test \
  -Ds3.endpoint=http://localhost:4566
```

### Against AWS EKS

When services are deployed to EKS, use `kubectl port-forward` to access RabbitMQ from outside the cluster:

```bash
# 1. Port-forward RabbitMQ
kubectl port-forward svc/rabbitmq 5672:5672 -n messaging &

# 2. Get the API Gateway URL
API_GW=$(kubectl get svc api-gateway -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

# 3. Run tests
mvn test \
  -Dauth.url=http://${API_GW} \
  -Dupload.url=http://${API_GW} \
  -Dprocessor.url=http://${API_GW} \
  -Dstatus.url=http://${API_GW} \
  -Dnotification.url=http://${API_GW} \
  -Drabbitmq.host=localhost \
  -Ds3.bucket=fiapx-videos-773171471185
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
  -Ds3.bucket=fiapx-videos
```

### Run from IDE

Run `CucumberRunnerTest.java` directly using the Run/Debug button. Add system properties in the Run configuration if services are not on default ports.

## CI/CD (GitHub Actions)

The workflow is triggered **manually** via `workflow_dispatch` (or `repository_dispatch`). It does **not** trigger on push or PR merge.

**To run:**
1. Go to the repository on GitHub
2. Click **Actions** → **CI - e2e-tests**
3. Click **Run workflow** → select the branch → **Run workflow**

The workflow automatically:
- Configures AWS credentials and kubeconfig for the EKS cluster
- Discovers service URLs via `kubectl get svc`
- Port-forwards RabbitMQ (`localhost:5672`) for the failure scenario test
- Runs `mvn test` with all properties configured
- Uploads Cucumber HTML/JSON reports as artifacts

### Required GitHub Secrets

| Secret | Description |
|---|---|
| `AWS_ACCESS_KEY_ID` | AWS credentials |
| `AWS_SECRET_ACCESS_KEY` | AWS credentials |
| `AWS_SESSION_TOKEN` | AWS session token (required for AWS Academy) |
| `RABBITMQ_USERNAME` | RabbitMQ user (default: `fiapx`) |
| `RABBITMQ_PASSWORD` | RabbitMQ password |
| `S3_BUCKET` | S3 bucket name (e.g. `fiapx-videos-773171471185`) |

## Configuration

All configuration is via system properties with sensible defaults:

| Property | Default | Description |
|---|---|---|
| `auth.url` | `http://localhost:8080` | auth-service base URL |
| `upload.url` | `http://localhost:8082` | upload-service base URL |
| `status.url` | `http://localhost:8084` | status-service base URL |
| `notification.url` | `http://localhost:8085` | notification-service base URL |
| `rabbitmq.host` | `localhost` | RabbitMQ host |
| `rabbitmq.port` | `5672` | RabbitMQ AMQP port |
| `rabbitmq.user` | `fiapx` | RabbitMQ user |
| `rabbitmq.pass` | `fiapx123` | RabbitMQ password |
| `s3.endpoint` | _(empty)_ | S3 endpoint (set for LocalStack, leave empty for real AWS) |
| `s3.bucket` | `fiapx-videos` | S3 bucket name |
| `auth.email` | `useradmin@email.com` | Test user email |
| `auth.password` | `Admin@12345` | Test user password |
| `poll.timeout` | `60` | Max seconds to wait for async results |

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
    │   ├── CucumberRunnerTest.java           # Cucumber JUnit 5 runner
    │   ├── config/
    │   │   └── TestConfig.java               # URLs, credentials, S3 client
    │   └── steps/
    │       └── VideoProcessingSagaSteps.java  # Step definitions
    └── resources/
        ├── features/
        │   └── video_processing_saga.feature  # Gherkin scenarios
        └── logback-test.xml
```
