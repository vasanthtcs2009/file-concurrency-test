# Developer Agent Instructions

## 1. Mission (The Project Context)
This is a high-performance telemetry ingestion and benchmarking platform designed to process massive concurrent writes under strict resource constraints. The system leverages Java 25 virtual threads, streaming JSON parsing, and semaphore-based backpressure to stream data into PostgreSQL and Redis concurrently. Preventing database connection pool exhaustion and maintaining an O(1) memory footprint are absolute architectural priorities.

## 2. Toolchain Registry (Commands First)

| Action | Command |
| :--- | :--- |
| **Build & Compile** | `./gradlew build` |
| **Run Unit & BDD Tests** | `./gradlew test` |
| **Run Application Locally** | `./gradlew bootRun` |
| **Start Infrastructure** | `docker-compose -f docker-compose-service.yml up -d` |
| **Stop Infrastructure** | `docker-compose -f docker-compose-service.yml down` |
| **Run Autonomous BDD Agent** | `python3 bdd_agent.py --file <java-file-path>` |

## 3. Java 21 Idioms & Identity
This project targets **Java 25**. Coding agents must strictly adhere to modern Java paradigms and avoid legacy constructs:
- **Concurrency:** Prefer Virtual Threads (`Thread.ofVirtual()` or `Executors.newVirtualThreadPerTaskExecutor()`) over platform thread pools for blocking database and I/O tasks.
- **Data Carriers:** Enforce the use of immutable `record` components for DTOs, key-value mappings, and internal messages.
- **Pattern Matching:** Require pattern matching for `switch` statements and Record Patterns to destructure data types. Avoid legacy cascaded `instanceof` casts.
- **Ordered Collections:** Use `SequencedCollection`, `SequencedSet`, or `SequencedMap` methods (e.g., `addFirst()`, `getFirst()`) when managing ordered telemetry sequences.

## 4. Judgment Boundaries (The 3-Tier Rule)

### ALWAYS (Autonomous Actions)
- Outline a detailed development plan before writing code.
- Run the full verification test suite (`./gradlew test`) before declaring any task complete.
- Handle all checked and unchecked exceptions explicitly—never swallow exceptions or log raw stack traces to `stdout`/`stderr`.
- Use Jackson's `JsonParser` streaming API to keep memory consumption at $O(1)$ when processing JSON documents.
- Respect connection pools and apply semaphore-based backpressure (`MAX_CONCURRENT_WRITES = 100`) to regulate blocking writes.

### ASK FIRST (Human-in-the-Loop Triggers)
- Ask before adding any new third-party dependencies to `build.gradle.kts`.
- Ask before changing architectural patterns or modifying major shared interfaces like `IngestionService` or repository contracts.
- Ask before generating structural database migrations or modifying PostgreSQL schema definitions.

### NEVER (Hard Enforcement Barriers)
- Never commit secrets, local `.env` variables, or raw API keys to the repository.
- Never write legacy pre-Java 21 concurrency constructs, such as manual platform thread pooling for simple blocking I/O.
- Never allow dynamic agent loading warnings to pollute build logs; ensure `-XX:+EnableDynamicAgentLoading` is used if bytecode manipulation tools are active.
