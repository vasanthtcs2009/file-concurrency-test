package com.example.file_concurrency_test.service;

import com.example.file_concurrency_test.model.TelemetryRecord;
import com.example.file_concurrency_test.repository.TelemetryRepository;
import com.example.file_concurrency_test.repository.TelemetryRedisRepository;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class IngestionService {

    private final TelemetryRepository telemetryRepository;
    private final TelemetryRedisRepository telemetryRedisRepository;
    private final Map<String, IngestionJob> jobs = new ConcurrentHashMap<>();

    // Concurrency limit for DB writes to match connection pool size
    private static final int MAX_CONCURRENT_WRITES = 100;
    private final Semaphore writeSemaphore = new Semaphore(MAX_CONCURRENT_WRITES);

    public IngestionService(TelemetryRepository telemetryRepository,
            TelemetryRedisRepository telemetryRedisRepository) {
        this.telemetryRepository = telemetryRepository;
        this.telemetryRedisRepository = telemetryRedisRepository;
    }

    @Data
    public static class IngestionJob {
        private String id;
        private String status = "PENDING"; // PENDING, RUNNING, COMPLETED, FAILED
        private final AtomicLong recordsRead = new AtomicLong(0);
        private final AtomicLong recordsWritten = new AtomicLong(0);
        private long startTime;
        private long endTime;
        private final AtomicLong writeTimeMs = new AtomicLong(0);
        private String errorMessage;
        private final AtomicInteger activeWriteThreads = new AtomicInteger(0);
        private int batchSize = 1000;
        private double fileSizeMb;
        // Redis parameters & metrics
        private String target = "both"; // postgres, redis, both
        private String redisStrategy = "PARALLEL_VT"; // PARALLEL_VT, PIPELINE
        private final AtomicLong redisWriteTimeMs = new AtomicLong(0);
    }

    public IngestionJob createJob(int batchSize, double fileSizeMb, String target, String redisStrategy) {
        IngestionJob job = new IngestionJob();
        job.setId(UUID.randomUUID().toString());
        job.setBatchSize(batchSize);
        job.setFileSizeMb(fileSizeMb);
        if (target != null) {
            job.setTarget(target.toLowerCase());
        }
        if (redisStrategy != null) {
            job.setRedisStrategy(redisStrategy.toUpperCase());
        }
        jobs.put(job.getId(), job);
        return job;
    }

    public IngestionJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    public List<IngestionJob> getJobs() {
        return new ArrayList<>(jobs.values());
    }

    public void startIngestion(String filePath, String jobId) {
        IngestionJob job = jobs.get(jobId);
        if (job == null) {
            log.error("Job {} not found", jobId);
            return;
        }

        job.setStatus("RUNNING");
        job.setStartTime(System.currentTimeMillis());

        // Spawn a virtual thread to handle the parsing and execution flow
        Thread.startVirtualThread(() -> {
            try {
                processIngestion(filePath, job);
            } catch (Exception e) {
                log.error("Ingestion failed for job {}", jobId, e);
                job.setStatus("FAILED");
                job.setErrorMessage(e.getMessage());
                job.setEndTime(System.currentTimeMillis());
            }
        });
    }

    private void processIngestion(String filePath, IngestionJob job) throws IOException, InterruptedException {
        log.info("Starting ingestion of {} with batch size {}", filePath, job.getBatchSize());

        JsonFactory factory = new JsonFactory();

        // Virtual thread executor for database write tasks
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                InputStream fis = new FileInputStream(filePath);
                InputStream bis = new BufferedInputStream(fis);
                JsonParser parser = factory.createParser(bis)) {

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException("Invalid JSON format. Expected start array token.");
            }

            List<TelemetryRecord> currentBatch = new ArrayList<>(job.getBatchSize());
            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                TelemetryRecord record = new TelemetryRecord();

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String fieldName = parser.currentName();
                    parser.nextToken(); // Move to the value token

                    if ("id".equals(fieldName)) {
                        record.setId(UUID.fromString(parser.getValueAsString()));
                    } else if ("deviceId".equals(fieldName)) {
                        record.setDeviceId(parser.getValueAsString());
                    } else if ("timestamp".equals(fieldName)) {
                        record.setTimestamp(LocalDateTime.parse(parser.getValueAsString(), formatter));
                    } else if ("status".equals(fieldName)) {
                        record.setStatus(parser.getValueAsString());
                    } else if (fieldName != null && fieldName.startsWith("metric_")) {
                        try {
                            int metricNum = Integer.parseInt(fieldName.substring(7));
                            int idx = metricNum - 1;
                            if (idx >= 0 && idx < 146) {
                                record.getMetrics()[idx] = parser.getValueAsDouble();
                            }
                        } catch (NumberFormatException e) {
                            // Ignore malformed metric fields
                        }
                    } else {
                        parser.skipChildren();
                    }
                }

                currentBatch.add(record);
                job.getRecordsRead().incrementAndGet();

                if (currentBatch.size() >= job.getBatchSize()) {
                    List<TelemetryRecord> batchToInsert = currentBatch;
                    currentBatch = new ArrayList<>(job.getBatchSize());

                    // Acquire permit before submitting to control concurrency (backpressure)
                    writeSemaphore.acquire();
                    job.getActiveWriteThreads().incrementAndGet();

                    executor.submit(() -> {
                        try {
                            performWrite(batchToInsert, job);
                        } catch (Exception e) {
                            log.error("Batch insert failed", e);
                            job.setStatus("FAILED");
                            job.setErrorMessage(e.getMessage());
                        } finally {
                            job.getActiveWriteThreads().decrementAndGet();
                            writeSemaphore.release();
                        }
                    });
                }
            }

            // Insert any remaining records
            if (!currentBatch.isEmpty()) {
                List<TelemetryRecord> batchToInsert = currentBatch;
                writeSemaphore.acquire();
                job.getActiveWriteThreads().incrementAndGet();

                executor.submit(() -> {
                    try {
                        performWrite(batchToInsert, job);
                    } catch (Exception e) {
                        log.error("Final batch insert failed", e);
                        job.setStatus("FAILED");
                        job.setErrorMessage(e.getMessage());
                    } finally {
                        job.getActiveWriteThreads().decrementAndGet();
                        writeSemaphore.release();
                    }
                });
            }

            // Wait for all virtual thread writes to complete
            executor.shutdown();
            boolean finished = executor.awaitTermination(30, TimeUnit.MINUTES);
            if (!finished) {
                log.warn("Ingestion executor termination timed out");
            }
        }

        job.setEndTime(System.currentTimeMillis());
        if (!"FAILED".equals(job.getStatus())) {
            job.setStatus("COMPLETED");
            long totalTimeMs = job.getEndTime() - job.getStartTime();
            log.info("Ingestion completed successfully for job {}. Total Ingestion Time: {} ms", job.getId(),
                    totalTimeMs);

            boolean writePg = "postgres".equals(job.getTarget()) || "both".equals(job.getTarget());
            boolean writeRedis = "redis".equals(job.getTarget()) || "both".equals(job.getTarget());
            long records = job.getRecordsWritten().get();

            if (writePg) {
                long pgTime = job.getWriteTimeMs().get();
                double pgThroughput = pgTime > 0 ? (records / (pgTime / 1000.0)) : 0.0;
                log.info(
                        "Postgres Ingestion Metrics - Job ID: {}, Total Cumulative Write Time: {} ms, Throughput: {} records/sec",
                        job.getId(), pgTime, String.format("%.2f", pgThroughput));
            }
            if (writeRedis) {
                long redisTime = job.getRedisWriteTimeMs().get();
                double redisThroughput = redisTime > 0 ? (records / (redisTime / 1000.0)) : 0.0;
                log.info(
                        "Redis Ingestion Metrics ({}) - Job ID: {}, Total Cumulative Write Time: {} ms, Throughput: {} records/sec",
                        job.getRedisStrategy(), job.getId(), redisTime, String.format("%.2f", redisThroughput));
            }
        }
    }

    private void performWrite(List<TelemetryRecord> batchToInsert, IngestionJob job) {
        boolean writePg = "postgres".equals(job.getTarget()) || "both".equals(job.getTarget());
        boolean writeRedis = "redis".equals(job.getTarget()) || "both".equals(job.getTarget());

        if (writePg && writeRedis) {
            // Write to both concurrently using virtual threads
            try (ExecutorService subExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                subExecutor.submit(() -> {
                    long pgStart = System.currentTimeMillis();
                    telemetryRepository.insertBatch(batchToInsert);
                    job.getWriteTimeMs().addAndGet(System.currentTimeMillis() - pgStart);
                });
                subExecutor.submit(() -> {
                    long redisStart = System.currentTimeMillis();
                    if ("PIPELINE".equals(job.getRedisStrategy())) {
                        telemetryRedisRepository.insertPipeline(batchToInsert);
                    } else {
                        telemetryRedisRepository.insertParallel(batchToInsert);
                    }
                    telemetryRedisRepository.incrementCount(batchToInsert.size());
                    job.getRedisWriteTimeMs().addAndGet(System.currentTimeMillis() - redisStart);
                });
                subExecutor.shutdown();
                if (!subExecutor.awaitTermination(5, TimeUnit.MINUTES)) {
                    log.warn("Concurrent write tasks timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Concurrent write operation interrupted", e);
            }
        } else if (writePg) {
            long pgStart = System.currentTimeMillis();
            telemetryRepository.insertBatch(batchToInsert);
            job.getWriteTimeMs().addAndGet(System.currentTimeMillis() - pgStart);
        } else if (writeRedis) {
            long redisStart = System.currentTimeMillis();
            if ("PIPELINE".equals(job.getRedisStrategy())) {
                telemetryRedisRepository.insertPipeline(batchToInsert);
            } else {
                telemetryRedisRepository.insertParallel(batchToInsert);
            }
            telemetryRedisRepository.incrementCount(batchToInsert.size());
            job.getRedisWriteTimeMs().addAndGet(System.currentTimeMillis() - redisStart);
        }

        job.getRecordsWritten().addAndGet(batchToInsert.size());
    }
}
