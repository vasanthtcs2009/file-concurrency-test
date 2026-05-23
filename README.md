# File Concurrency Test - Telemetry Data Ingestion Platform

A high-performance Spring Boot application for concurrent telemetry data ingestion from JSON files using Java virtual threads, designed to test file processing and database write concurrency patterns.

## 📋 Overview

This application demonstrates efficient handling of large-scale telemetry data ingestion with:
- **JSON streaming parser** for memory-efficient file processing
- **Virtual thread pool** executor for non-blocking concurrent writes
- **Semaphore-based backpressure** to manage database connection pool saturation
- **Real-time job monitoring** with detailed metrics and progress tracking
- **PostgreSQL** for persistent storage with 146 metrics per telemetry record

## 🏗️ Architecture

### Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| **JDK** | Java | 25 |
| **Framework** | Spring Boot | 4.0.6 |
| **Database** | PostgreSQL | 16 (Alpine) |
| **Build Tool** | Gradle | Latest |
| **Database ORM** | Spring Data JPA | Via Spring Boot Starter |
| **Annotations** | Lombok | Latest |
| **JSON Processing** | Jackson | 2.x |
| **Testing** | JUnit 5 | Latest |
| **Code Coverage** | JaCoCo | Latest |

### Project Structure

```
file-concurrency-test/
├── src/
│   ├── main/java/com/example/file_concurrency_test/
│   │   ├── FileConcurrencyTestApplication.java    # Spring Boot entry point
│   │   ├── controller/
│   │   │   └── TelemetryController.java           # REST API endpoints
│   │   ├── service/
│   │   │   ├── TelemetryGenerator.java            # JSON test data generation
│   │   │   └── IngestionService.java              # Concurrent ingestion engine
│   │   ├── repository/
│   │   │   └── TelemetryRepository.java           # Database access layer
│   │   └── model/
│   │       └── TelemetryRecord.java               # Domain model
│   └── test/java/                                 # Test suite
├── docker-compose.yml                             # PostgreSQL setup
├── build.gradle.kts                               # Gradle configuration
└── README.md                                      # This file
```

## 🚀 Getting Started

### Prerequisites

- Java 25+
- Docker & Docker Compose
- Gradle

### Setup & Execution

1. **Start PostgreSQL Database**
   ```bash
   docker-compose up -d
   ```

2. **Build the Application**
   ```bash
   ./gradlew build
   ```

3. **Run the Application**
   ```bash
   ./gradlew bootRun
   ```
   
   Application will be available at `http://localhost:8080`

4. **Generate Test Data**
   ```bash
   curl -X POST "http://localhost:8080/api/telemetry/generate?count=50000"
   ```

5. **Import Data (Start Ingestion)**
   ```bash
   curl -X POST "http://localhost:8080/api/telemetry/import?batchSize=1000"
   ```

6. **Check Job Status**
   ```bash
   curl "http://localhost:8080/api/telemetry/status"
   ```

## 📡 API Endpoints

### Data Generation
- **POST** `/api/telemetry/generate?count=50000`
  - Generates JSON test file with telemetry records
  - Returns: File path, size (MB), and generation time (ms)

### Data Ingestion
- **POST** `/api/telemetry/import?batchSize=1000`
  - Starts asynchronous ingestion job
  - Returns: Job ID, status, and configuration

### Job Monitoring
- **GET** `/api/telemetry/status/{jobId}`
  - Returns detailed metrics for a specific job
  - Includes: records read/written, throughput, elapsed time, error messages

- **GET** `/api/telemetry/status`
  - Returns all active jobs and database row count
  - Useful for monitoring multiple concurrent ingestions

### Data Sampling
- **GET** `/api/telemetry/sample`
  - Returns 100 sample records from the database
  - Useful for verification and testing

### Database Management
- **DELETE** `/api/telemetry/clear`
  - Truncates database and deletes generated JSON file
  - Use for cleanup between test runs

## 🔧 Configuration

### Database Connection
Configure in `application.properties` (or `application.yml`):
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/telemetry_db
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.datasource.hikari.maximum-pool-size=50
```

### Key Tuning Parameters (IngestionService)
```java
MAX_CONCURRENT_WRITES = 45  // Semaphore permits (matches connection pool)
```

This value should align with your PostgreSQL connection pool size to prevent saturation.

## 📊 Data Model

### TelemetryRecord
```java
{
  "id": "UUID",
  "deviceId": "String (e.g., DEV-1234)",
  "timestamp": "LocalDateTime (ISO format)",
  "status": "OK|WARNING|CRITICAL",
  "metric_1" to "metric_146": "double values (10.0 - 100.0)"
}
```

### Database Schema
```sql
CREATE TABLE telemetry_records (
  id UUID PRIMARY KEY,
  device_id VARCHAR(255) NOT NULL,
  timestamp TIMESTAMP NOT NULL,
  status VARCHAR(50),
  metric_1 to metric_146 DOUBLE PRECISION
);
```

## ⚙️ Core Components

### 1. TelemetryController (REST API)
- Orchestrates data generation and ingestion workflows
- Provides real-time monitoring endpoints
- Manages database lifecycle (truncate/clear operations)

### 2. TelemetryGenerator (Service)
- Generates high-volume JSON test files
- Uses Jackson's JsonGenerator for streaming (memory-efficient)
- Produces realistic telemetry data with randomized metrics

### 3. IngestionService (Service)
- **Virtual Thread Pool**: Executes batch write tasks on virtual threads
- **Semaphore-Based Backpressure**: Controls concurrent database writes
- **Job Management**: Tracks ingestion progress and metrics
- **Error Handling**: Captures and reports failures with job state updates

### 4. TelemetryRepository (Data Access)
- Batch insert operations using JdbcTemplate
- Optimized for 146-metric inserts per record
- Dynamic SQL generation to reduce overhead
- Count, truncate, and sampling operations

## 📈 Performance Characteristics

### Concurrency Model
- **File Reading**: Single-threaded streaming via Jackson JsonParser
- **Parsing**: Single thread (no bottleneck due to I/O efficiency)
- **Database Writes**: Up to 45 virtual threads (configurable)
- **Batch Size**: Configurable (default: 1000 records/batch)

### Memory Efficiency
- **Streaming JSON parsing** prevents loading entire file into memory
- **Virtual thread pool** avoids context switch overhead
- **Buffered I/O** optimizes file and network throughput

### Throughput Monitoring
Each job response includes:
```json
{
  "jobId": "uuid",
  "status": "RUNNING|COMPLETED|FAILED",
  "recordsRead": 50000,
  "recordsWritten": 50000,
  "activeWriteThreads": 12,
  "throughputRecordsPerSec": 8333.33,
  "writeTimeMs": 6000,
  "elapsedTimeMs": 6500
}
```

## 🧪 Testing

Run the test suite:
```bash
./gradlew test
```

Test classes:
- `TelemetryControllerTest` - REST endpoint validation
- `IngestionServiceTest` - Concurrency and error handling
- `TelemetryGeneratorTest` - Data generation accuracy
- `TelemetryRepositoryTest` - Database operations
- `TelemetryRecordTest` - Domain model validation

Code coverage reports are generated in `build/reports/jacoco/`

## 📚 Documentation

- **[ARCHITECTURE.md](./docs/ARCHITECTURE.md)** - Detailed L1 & L2 diagrams and component descriptions
- **[DESIGN_DECISIONS.md](./docs/DESIGN_DECISIONS.md)** - Rationale behind key design choices
- **[CONCURRENCY_MODEL.md](./docs/CONCURRENCY_MODEL.md)** - Virtual threads, semaphores, and backpressure explanation

## 🔍 Troubleshooting

### Job Hangs or Times Out
- Check PostgreSQL connection pool availability
- Verify database is running: `docker ps`
- Increase MAX_CONCURRENT_WRITES if pool size allows

### Out of Memory
- Reduce batch size or record count
- Monitor JVM heap: `./gradlew bootRun --jvmArgs="-Xmx2g"`

### Database Connection Errors
- Ensure PostgreSQL is running
- Check credentials in application.properties
- Verify network connectivity

## 📝 License

This project is part of the Antigravity framework for testing concurrent data processing patterns.

## 🤝 Contributing

Submit issues and PRs to improve performance, add features, or enhance documentation.
