# GitHub Copilot Custom Instructions

These instructions help GitHub Copilot generate code, suggest refactorings, and provide answers that align with the architecture, style, and constraints of the **Telemetry Ingestion Platform**.

---

## 1. Project Context & Stack

This is a high-performance concurrent telemetry ingestion and benchmarking platform designed to process massive datasets under strict resource constraints.

- **JDK Version:** Java 25 (utilizing Virtual Threads, modern pattern matching, and record destructuring).
- **Core Framework:** Spring Boot 4.0.6, Spring Data JPA, Spring Data Redis.
- **Relational Database:** PostgreSQL (wide-table telemetry records, 146 sensor columns).
- **In-Memory Cache:** Redis (high-performance pipelined and parallel VT caching).
- **Build Tool:** Gradle.
- **JSON Parsing:** Jackson Streaming API (`JsonParser`) for $O(1)$ memory consumption.

---

## 2. Java 25 Coding Standards & Idioms

Always generate code using modern Java paradigms. Avoid legacy constructs:

### ⚡ Concurrency & I/O
- Prefer **Virtual Threads** for all blocking I/O and database operations.
- Use `Executors.newVirtualThreadPerTaskExecutor()` or `Thread.startVirtualThread(...)`.
- **Never** write manual platform thread pooling (e.g., `Executors.newFixedThreadPool(...)`) for simple blocking tasks.

### 📦 Data Carriers & Immutable Structures
- Enforce the use of immutable `record` structures for DTOs, messages, and key-value mapping payloads.
- Lombok is configured for mutable entities and status tracking models (e.g., `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`). Use Lombok responsibly and only where mutability is required.

### 🔀 Pattern Matching & Switch Blocks
- Use **Pattern Matching for switch** and **Record Patterns** to destructure records and handle type checks cleanly.
- **Never** write verbose `instanceof` with manual casting blocks.

### 🗄️ Collections
- Use **Sequenced Collections** (`SequencedCollection`, `SequencedSet`, `SequencedMap`) for collections where order is stable.
- Call methods like `.getFirst()`, `.getLast()`, `.addFirst()`, or `.addLast()` instead of indexing or iterator retrieval.

---

## 3. Architecture & Operational Rules

### ⚓ Backpressure & Pools
- Ensure database connection pool limit constraints are respected. 
- Apply semaphore-based backpressure (`MAX_CONCURRENT_WRITES = 100`) in `IngestionService` to regulate concurrent database write tasks and prevent HikariCP pool exhaustion.

### 💾 Performance-Optimized Storage
- **PostgreSQL Writes:** Use bulk batch updates via `JdbcTemplate.batchUpdate` with `BatchPreparedStatementSetter` for high-speed wide-table writes.
- **Redis Writes:** Support two execution patterns:
  1. `PARALLEL_VT`: Mapping each record in a batch to a single virtual thread performing synchronous Redis writes.
  2. `PIPELINE`: Pipelining command batches to bypass round-trip latency.

### 📊 Streaming Parsing
- When reading telemetry JSON files, use Jackson's streaming tokens (`JsonParser.nextToken()`, `JsonToken.START_OBJECT`, etc.) to process the stream with $O(1)$ heap allocation. Do not parse the entire file into memory at once.

---

## 4. Testing & Verification

- **Verification:** Always run `./gradlew test` to verify compilations and unit/integration tests before finalizing changes.
- **BDD Cucumber Tests:** Integration tests use Gherkin feature files (located under `src/test/resources/features/`). Ensure any new capabilities are accompanied by Cucumber step definitions.

---

## 5. Development Command Registry

| Intent | Command |
| :--- | :--- |
| **Build & Compile** | `./gradlew build` |
| **Run Tests** | `./gradlew test` |
| **Run Locally** | `./gradlew bootRun` |
| **Spin Up Infra** | `docker-compose -f docker-compose-service.yml up -d` |
| **Stop Infra** | `docker-compose -f docker-compose-service.yml down` |
| **BDD Generator** | `python3 bdd_agent.py --file <file-path>` |
