# 🧠 Design Decisions - Telemetry Ingestion Platform

This document outlines the architectural trade-offs and decisions made during the design of the Telemetry Ingestion Platform to achieve high performance, memory efficiency, and stability.

---

## 1. Jackson Streaming API vs. Full-Object Data Binding

### Context
Telemetry files generated for testing contain large arrays of JSON objects. At a count of 100,000 records, files exceed 150MB. Under heavy load, multiple files could be ingested concurrently.

### Options Considered
1. **Full-Object Binding (`ObjectMapper.readValue`)**: Deserialize the entire JSON array directly into a `List<TelemetryRecord>` or `TelemetryRecord[]`.
2. **Streaming Parser (`JsonParser`)**: Manually traverse JSON tokens sequentially and construct individual `TelemetryRecord` instances on the fly.

### Decision & Rationale
We chose **Option 2 (Streaming Parser)**.
- **Memory Efficiency**: Ingesting using direct object mapping requires loading the entire file structure into the JVM heap. This leads to high GC pause times and potential `OutOfMemoryError` (OOM) failures under concurrent execution.
- **Low Overhead**: The streaming parser processes tokens sequentially. We maintain only the current batch size (e.g., 1,000 records) in memory. Once a batch is handed off to the database executor, the references are cleared and garbage collected, resulting in a flat $O(1)$ memory footprint relative to the file size.

---

## 2. Java 25 Virtual Threads vs. Platform Thread Pools

### Context
Writing records to a relational database over JDBC is a classic blocking I/O operation. A thread must block and wait for the database roundtrip to complete.

### Options Considered
1. **Fixed Platform Thread Pool (`Executors.newFixedThreadPool`)**: Use a pool of heavy, OS-level threads.
2. **Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor`)**: Use lightweight Java virtual threads introduced in JDK 21.

### Decision & Rationale
We chose **Option 2 (Virtual Threads)**.
- **Resource Constraints**: Platform threads are expensive to create, consuming up to 1MB of memory for their stack, and they incur high context-switching overhead.
- **Blocking-Friendly Scheduling**: Virtual threads are managed by the JVM and run on top of a small carrier thread pool. When a virtual thread blocks on a database write, the JVM detaches it from the carrier thread, allowing other virtual threads to execute. This achieves massive concurrency without resource starvation or complex reactive programming paradigms.

---

## 3. Spring Data JPA (Hibernate) vs. Direct JDBC Template

### Context
Each telemetry record contains a flat list of 146 double-precision metric fields. A single batch of 1,000 records represents 150,000 parameters to write.

### Options Considered
1. **Spring Data JPA / Hibernate**: Save entities using `JpaRepository.saveAll()`.
2. **Spring JDBC Template**: Perform low-level batch inserts using `JdbcTemplate.batchUpdate()` and raw SQL.

### Decision & Rationale
We chose **Option 2 (Spring JDBC Template)**.
- **Write Performance**: Hibernate adds substantial overhead due to persistence context management, dirty checking, entity state tracking, and dynamic SQL generation. For 146-column tables, this causes massive CPU consumption inside the JVM.
- **Deterministic Batching**: JPA batch inserts require careful configuration (disabling identity generation, configuring specific properties like `rewriteBatchedInserts`). In contrast, `JdbcTemplate` maps directly to JDBC batching APIs, ensuring that batches are sent in a single roundtrip to PostgreSQL, optimizing network efficiency.
- **Pre-Compiled Query**: By building the insert query string once during `@PostConstruct` in `TelemetryRepository`, we minimize string allocation overhead during execution.

---

## 4. Semaphore-based Backpressure vs. Unbounded Submission

### Context
A fast local file reader can parse hundreds of thousands of JSON records per second, which is much faster than a relational database can persist them.

### Options Considered
1. **Unbounded Submission**: Submit batch write tasks directly to the executor as soon as they are parsed, without restriction.
2. **Bounded Queue / Semaphore**: Restrict the number of concurrent database write tasks using a concurrency governor.

### Decision & Rationale
We chose **Option 2 (Semaphore-based Backpressure)**.
- **Preventing Exhaustion**: Unbounded task submission would parser-read the file in seconds, spawning thousands of virtual threads. All of these threads would attempt to acquire a database connection from the Hikari pool. This would exhaust the pool, trigger connection timeouts (`SQLTransientConnectionException`), and balloon heap memory as all parsed records wait in memory.
- **Rate Synchronization**: By introducing a `Semaphore` with `45` permits (matching the Hikari pool's size of 50), the system ensures that at most 45 batch inserts are active. If all 45 slots are busy, the main parsing thread blocks on `writeSemaphore.acquire()`. This naturally throttles the parser speed to match the database write speed, stabilizing heap usage and database pool connections.
