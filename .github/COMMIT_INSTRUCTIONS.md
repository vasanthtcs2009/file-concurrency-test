# 🛠️ Git Commit Conventions & Instructions

This repository follows a strict commit message convention that combines **Conventional Commits** with **Gitmoji** to ensure a clean, searchable, and readable commit history.

---

## 📝 Commit Message Structure

Every commit message must follow this format:

```text
<gitmoji> <type>(<scope>): <subject>

[Optional body describing the 'why' and 'what' in detail]

[Optional footer(s) listing breaking changes or issue tracker references]
```

### Example:
```text
✨ feat(ingestion): add support for virtual thread database writes

Implemented a virtual-thread-per-task executor in IngestionService to scale database inserts concurrently without blocking OS threads. 

Resolves: #104
```

---

## 🎨 Gitmoji Reference Guide

Use the following gitmojis to categorize the purpose of your commit:

| Gitmoji | Code | Meaning / Use Case |
| :---: | :--- | :--- |
| ✨ | `:sparkles:` | New features and capabilities |
| 🐛 | `:bug:` | Bug fixes |
| 📝 | `:memo:` | Documentation updates (README, docs, code comments) |
| ⚡ | `:zap:` | Performance improvements (e.g., Virtual Threads, connection pools) |
| 🏗️ | `:building_construction:` | Architectural or project configuration changes (Gradle setup) |
| 🐳 | `:whale:` | Docker configurations and containerization |
| ✅ | `:white_check_mark:` | Adding, updating, or fixing tests |
| ♻️ | `:recycle:` | Refactoring code without changing functionality |
| 🔊 | `:loud_sound:` | Adding or improving logging and metrics |
| 🔧 | `:wrench:` | Changing configuration files (properties, YAML) |
| ⬇️ | `:arrow_down:` | Downgrading dependencies |
| ⬆️ | `:arrow_up:` | Upgrading dependencies |
| 🔒 | `:lock:` | Security updates or fixing vulnerabilities |

---

## ☕ Java 25 & Project Scopes

The `<scope>` should represent the component or domain of the project being changed. Common scopes in this repository include:

*   **`virtual-threads`**: Concurrency models, semaphores, or thread executors.
*   **`ingestion`**: Streaming parser, CSV/JSON file ingestion logic, or service components.
*   **`db`**: JPA entities, schema definitions, repositories, or connection pooling optimizations.
*   **`redis`**: Cache strategies, pipelining, or Redis repositories.
*   **`monitoring`**: Prometheus, Grafana, ELK configurations, or Actuator metrics.
*   **`docker`**: Dockerfiles, Docker Compose, or networking.
*   **`deps`**: Gradle files or Java versions.

---

## 🚀 Step-by-Step Commit Guide

### 1. Stage Your Changes
Select the files you want to include in the commit:
```bash
git add src/main/java/com/example/file_concurrency_test/service/IngestionService.java
```

### 2. Write Your Commit Message
Compose the commit using gitmojis. You can write the gitmoji code directly (like `:loud_sound:`) and Git/GitHub will render the emoji:

```bash
git commit -m "🔊 log(ingestion): split telemetry write duration logging for PG and Redis" \
-m "Added specific elapsed time calculations for both database targets in IngestionService." \
-m "Now prints independent throughput stats at job completion."
```

### 3. (Optional) Use Gitmoji CLI
If you want interactive prompt support for writing Gitmoji commits, you can use the official `gitmoji-cli`:

1.  **Install Gitmoji CLI globally**:
    ```bash
    npm install -g gitmoji-cli
    ```
2.  **Initialize or run interactive commit**:
    ```bash
    gitmoji -c
    ```

---

## 📋 Examples for Recent Repository Changes

Here are exact examples of commit messages matching recent changes in this repository:

#### Split Ingestion Logging:
```text
🔊 log(ingestion): add separate metrics logging for Postgres and Redis

- Calculated total elapsed write times for PostgreSQL and Redis separately.
- Logged batch average write times and calculated records throughput at completion.
- Updated frontend log console to display individual target write times.
```

#### Monitoring Setup:
```text
🏗️ monitoring(elk): integrate ELK stack, Prometheus, and Grafana into compose

- Configured Elasticsearch, Logstash, Kibana, Prometheus, and Grafana in docker-compose.
- Added Logstash pipeline filters and Prometheus target scrapers.
- Instrumented Spring Boot application with micrometer registries and TCP logger appender.
```

#### Containerization:
```text
🐳 docker(app): containerize Spring Boot service and isolate networks

- Created a multi-stage Dockerfile using eclipse-temurin:25 JDK/JRE alpine images.
- Isolated postgres/redis from monitoring tools by dividing them into two separate docker-compose files.
- Defined custom external app-network (file-concurrency-network) to link container communications.
```
