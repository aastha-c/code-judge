# ⚡ Mini Online Code Judge Backend

A high-throughput, production-grade Online Code Judge backend (similar to HackerRank / LeetCode) built with **Spring Boot 3**, **Java 21 (Virtual Threads / Project Loom)**, **Docker Engine (`docker-java`)**, **PostgreSQL**, **Redis**, and **RabbitMQ**.

---

## 🌟 Key Architecture & Capabilities

- **Programmatic Docker Sandbox (`DockerSandboxService`)**:
  - Dynamically provisions ephemeral runner containers for untrusted user code (Python, Java, C++).
  - **Hardware Resource Limits**: Hard-capped memory at 256MB (`--memory=256m`), swap disabled, CPU throttled to 0.5 cores (`--cpus=0.5`), and process limit capped at 64 (`--pids-limit=64`) to prevent fork-bombs.
  - **Air-Gapped Isolation**: Network stack completely disabled (`--network=none`), root filesystem mounted read-only (`--read-only`), read-only host mount for user source code, and unprivileged non-root execution (`1001:1001`).
  - **Execution Timeout Handling**: Strict 2.0s execution timeout handler with forcible container termination (`SIGKILL`) and guaranteed cleanup in a `finally` block.
- **Asynchronous Ingestion Pipeline**:
  - `POST /api/v1/submissions` saves code submissions as `PENDING` in PostgreSQL, dispatches the `submissionId` to RabbitMQ, and returns `HTTP 202 Accepted` immediately.
  - `SubmissionWorker` (`@RabbitListener`) asynchronously consumes jobs, executes tests against sandbox containers, compiles (if compiled language), compares program `stdout` against expected test cases, and persists final verdicts (`ACCEPTED`, `WRONG_ANSWER`, `TIME_LIMIT_EXCEEDED`, `COMPILATION_ERROR`, `RUNTIME_ERROR`).
- **Real-Time Polling Endpoint**:
  - `GET /api/v1/submissions/{id}` allows clients to poll execution status, retrieve memory/time metrics, and view detailed output logs or error traces.
- **Real-Time Leaderboard (`LeaderboardService`)**:
  - Backed by **Redis Sorted Sets** (`ZADD` / `ZREVRANGE`) updating user scores and retrieving ranks in $O(\log N)$ time.

---

## 🏗️ Tech Stack

- **Language**: Java 21 (Virtual Threads enabled)
- **Framework**: Spring Boot 3.3.x (Spring Web, Spring Data JPA, Spring AMQP, Spring Security)
- **Message Broker**: RabbitMQ 3.12
- **Database**: PostgreSQL 16
- **Cache & Leaderboard**: Redis 7
- **Sandbox Engine**: `docker-java` SDK 3.3.6 (HTTP Client 5)

---

## 📁 Project Structure

```
code-judge/
├── docker-compose.yml              # Local infrastructure (Postgres, Redis, RabbitMQ)
├── pom.xml                         # Maven dependencies & build setup
├── README.md                       # Documentation & setup guide
└── src/
    └── main/
        ├── java/
        │   └── com/
        │       └── example/
        │           └── codejudge/
        │               ├── CodeJudgeApplication.java      # Main entry point
        │               ├── config/
        │               │   ├── DockerConfig.java          # docker-java client bean
        │               │   ├── RabbitMQConfig.java        # Queues, exchange & bindings
        │               │   └── SecurityConfig.java        # Security filter chain
        │               ├── controller/
        │               │   └── SubmissionController.java  # Ingestion & Polling endpoints
        │               ├── dto/
        │               │   ├── SubmissionRequest.java     # Submission payload
        │               │   └── SubmissionResponseDto.java # Status & verdict response
        │               ├── entity/
        │               │   ├── Submission.java            # JPA submission model
        │               │   ├── SubmissionStatus.java      # PENDING, IN_PROGRESS, COMPLETED...
        │               │   ├── TestCase.java              # Problem test cases
        │               │   └── Verdict.java               # AC, WA, TLE, CE, RE, MLE
        │               ├── repository/
        │               │   ├── SubmissionRepository.java  # PostgreSQL JPA repository
        │               │   └── TestCaseRepository.java    # Test case JPA repository
        │               ├── service/
        │               │   ├── DockerSandboxService.java  # Docker execution & cgroup isolation
        │               │   └── LeaderboardService.java    # Redis Sorted Set leaderboard
        │               └── worker/
        │                   └── SubmissionWorker.java      # Asynchronous evaluation worker
        └── resources/
            └── application.yml                            # Spring Boot configuration
```

---

## 🚀 Getting Started

### 1. Prerequisites
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (running)
- [Java 21 JDK](https://adoptium.net/)
- [Maven 3.9+](https://maven.apache.org/)

### 2. Start Supporting Infrastructure
Run the included `docker-compose.yml` to spin up PostgreSQL, Redis, and RabbitMQ:

```bash
docker compose up -d
```

| Service | Port | Credentials |
| :--- | :--- | :--- |
| **PostgreSQL** | `5432` | `judgedb` / `postgres` / `postgres` |
| **Redis** | `6379` | *None* |
| **RabbitMQ AMQP** | `5672` | `guest` / `guest` |
| **RabbitMQ Dashboard** | `15672` | `guest` / `guest` (http://localhost:15672) |

### 3. Pre-pull Sandbox Runner Images
Pre-pulling the container images ensures the first evaluation runs without image-download delays:

```bash
docker pull python:3.11-slim
docker pull openjdk:21-slim
docker pull gcc:latest
```

### 4. Build and Run Application
```bash
mvn clean spring-boot:run
```
Or open the project in **IntelliJ IDEA** and click the green **Run** button next to `CodeJudgeApplication.java`.

---

## 🔌 API Endpoints

### 1. Submit Code for Evaluation
- **Endpoint**: `POST /api/v1/submissions`
- **Status**: `202 Accepted`

```json
{
  "userId": 101,
  "problemId": 1,
  "language": "python",
  "sourceCode": "import sys\nlines = sys.stdin.read().split()\nprint(int(lines[0]) + int(lines[1]))"
}
```

**Response**:
```json
{
  "submissionId": 1,
  "problemId": 1,
  "language": "python",
  "status": "PENDING",
  "createdAt": "2026-10-07T15:30:00"
}
```

---

### 2. Poll Submission Verdict & Logs
- **Endpoint**: `GET /api/v1/submissions/{id}`
- **Status**: `200 OK`

**Response (After Evaluation)**:
```json
{
  "submissionId": 1,
  "problemId": 1,
  "language": "python",
  "status": "COMPLETED",
  "verdict": "ACCEPTED",
  "executionTimeMs": 64,
  "memoryUsedKb": null,
  "outputLogs": "15",
  "errorMessage": null,
  "createdAt": "2026-10-07T15:30:00"
}
```

---

## 🔒 Security Sandboxing Specifications

| Constraint | Configuration | Purpose |
| :--- | :--- | :--- |
| **Memory** | `--memory=256m`, `--memory-swap=256m` | Prevents host Out-Of-Memory exhaustion |
| **CPU** | `--nano-cpus=500000000` (0.5 core) | Throttles CPU spikes |
| **Processes** | `--pids-limit=64` | Prevents fork-bomb denial-of-service |
| **Network** | `--network=none` | Blocks unauthorized outbound/inbound requests |
| **Filesystem** | `--read-only` | Root filesystem cannot be tampered with |
| **Temporary Files**| `--tmpfs /tmp:rw,noexec,nosuid,size=64m` | Controlled in-memory ephemeral workspace |
| **Privileges** | `CapDrop(ALL)`, user `1001:1001` | Strips all Linux root capabilities |
