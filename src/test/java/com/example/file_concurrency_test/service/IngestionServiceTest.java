package com.example.file_concurrency_test.service;

import com.example.file_concurrency_test.model.TelemetryRecord;
import com.example.file_concurrency_test.repository.TelemetryRepository;
import com.example.file_concurrency_test.repository.TelemetryRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock
    private TelemetryRepository telemetryRepository;

    @Mock
    private TelemetryRedisRepository telemetryRedisRepository;

    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new IngestionService(telemetryRepository, telemetryRedisRepository);
    }

    @Test
    void testJobCrud() {
        IngestionService.IngestionJob job = ingestionService.createJob(100, 1.5, "postgres", "PARALLEL_VT");
        assertNotNull(job);
        assertNotNull(job.getId());
        assertEquals(100, job.getBatchSize());
        assertEquals(1.5, job.getFileSizeMb());
        assertEquals("PENDING", job.getStatus());
        assertEquals("postgres", job.getTarget());
        assertEquals("PARALLEL_VT", job.getRedisStrategy());

        assertEquals(job, ingestionService.getJob(job.getId()));
        assertTrue(ingestionService.getJobs().contains(job));

        assertNull(ingestionService.getJob("non-existent"));
    }

    @Test
    void testStartIngestionNonExistentJob() {
        // Should log error and return without raising exception
        assertDoesNotThrow(() -> ingestionService.startIngestion("dummyPath.json", "non-existent-id"));
    }

    @Test
    void testProcessIngestionSuccess(@TempDir Path tempDir) throws IOException, InterruptedException {
        File tempFile = tempDir.resolve("valid_telemetry.json").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("[\n" +
                    "  {\n" +
                    "    \"id\": \"c3a04294-f2a8-48b4-bf5a-4b953835ee3f\",\n" +
                    "    \"deviceId\": \"DEV-1001\",\n" +
                    "    \"timestamp\": \"2026-05-22T10:00:00\",\n" +
                    "    \"status\": \"OK\",\n" +
                    "    \"metric_1\": 10.5,\n" +
                    "    \"metric_146\": 99.9\n" +
                    "  },\n" +
                    "  {\n" +
                    "    \"id\": \"d7d06e23-74b8-4c92-bd1a-96ad2c66d21b\",\n" +
                    "    \"deviceId\": \"DEV-1002\",\n" +
                    "    \"timestamp\": \"2026-05-22T10:01:00\",\n" +
                    "    \"status\": \"WARNING\",\n" +
                    "    \"metric_2\": 20.5\n" +
                    "  },\n" +
                    "  {\n" +
                    "    \"id\": \"e4d06e23-74b8-4c92-bd1a-96ad2c66d21c\",\n" +
                    "    \"deviceId\": \"DEV-1003\",\n" +
                    "    \"timestamp\": \"2026-05-22T10:02:00\",\n" +
                    "    \"status\": \"CRITICAL\",\n" +
                    "    \"metric_10\": 50.0\n" +
                    "  }\n" +
                    "]");
        }

        IngestionService.IngestionJob job = ingestionService.createJob(2, 0.01, "postgres", "PARALLEL_VT");
        ingestionService.startIngestion(tempFile.getAbsolutePath(), job.getId());

        awaitJobCompletion(job);

        assertEquals("COMPLETED", job.getStatus());
        assertEquals(3, job.getRecordsRead().get());
        assertEquals(3, job.getRecordsWritten().get());
        assertNull(job.getErrorMessage());
        assertTrue(job.getWriteTimeMs().get() >= 0);
        verify(telemetryRepository, times(2)).insertBatch(anyList());
    }

    @Test
    void testProcessIngestionInvalidJsonFormat(@TempDir Path tempDir) throws IOException, InterruptedException {
        File tempFile = tempDir.resolve("invalid_telemetry.json").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("{ \"notAnArray\": true }");
        }

        IngestionService.IngestionJob job = ingestionService.createJob(10, 0.01, "postgres", "PARALLEL_VT");
        ingestionService.startIngestion(tempFile.getAbsolutePath(), job.getId());

        awaitJobCompletion(job);

        assertEquals("FAILED", job.getStatus());
        assertNotNull(job.getErrorMessage());
        assertTrue(job.getErrorMessage().contains("Invalid JSON format"));
        verifyNoInteractions(telemetryRepository);
    }

    @Test
    void testProcessIngestionRepositoryException(@TempDir Path tempDir) throws IOException, InterruptedException {
        File tempFile = tempDir.resolve("valid_telemetry.json").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("[\n" +
                    "  {\n" +
                    "    \"id\": \"c3a04294-f2a8-48b4-bf5a-4b953835ee3f\",\n" +
                    "    \"deviceId\": \"DEV-1001\",\n" +
                    "    \"timestamp\": \"2026-05-22T10:00:00\",\n" +
                    "    \"status\": \"OK\"\n" +
                    "  }\n" +
                    "]");
        }

        doThrow(new RuntimeException("Database error")).when(telemetryRepository).insertBatch(anyList());

        IngestionService.IngestionJob job = ingestionService.createJob(1, 0.01, "postgres", "PARALLEL_VT");
        ingestionService.startIngestion(tempFile.getAbsolutePath(), job.getId());

        awaitJobCompletion(job);

        assertEquals("FAILED", job.getStatus());
        assertEquals("Database error", job.getErrorMessage());
        verify(telemetryRepository).insertBatch(anyList());
    }

    @Test
    void testProcessIngestionFinalBatchException(@TempDir Path tempDir) throws IOException, InterruptedException {
        File tempFile = tempDir.resolve("valid_telemetry.json").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("[\n" +
                    "  {\n" +
                    "    \"id\": \"c3a04294-f2a8-48b4-bf5a-4b953835ee3f\",\n" +
                    "    \"deviceId\": \"DEV-1001\",\n" +
                    "    \"timestamp\": \"2026-05-22T10:00:00\",\n" +
                    "    \"status\": \"OK\"\n" +
                    "  },\n" +
                    "  {\n" +
                    "    \"id\": \"d7d06e23-74b8-4c92-bd1a-96ad2c66d21b\",\n" +
                    "    \"deviceId\": \"DEV-1002\",\n" +
                    "    \"timestamp\": \"2026-05-22T10:01:00\",\n" +
                    "    \"status\": \"OK\"\n" +
                    "  },\n" +
                    "  {\n" +
                    "    \"id\": \"e4d06e23-74b8-4c92-bd1a-96ad2c66d21c\",\n" +
                    "    \"deviceId\": \"DEV-1003\",\n" +
                    "    \"timestamp\": \"2026-05-22T10:02:00\",\n" +
                    "    \"status\": \"OK\"\n" +
                    "  }\n" +
                    "]");
        }

        // Succeed on first batch (which holds 2 records if batchSize=2), fail on the second batch (final batch of 1)
        doNothing().doThrow(new RuntimeException("Final batch database error"))
                .when(telemetryRepository).insertBatch(anyList());

        IngestionService.IngestionJob job = ingestionService.createJob(2, 0.01, "postgres", "PARALLEL_VT");
        ingestionService.startIngestion(tempFile.getAbsolutePath(), job.getId());

        awaitJobCompletion(job);

        assertEquals("FAILED", job.getStatus());
        assertEquals("Final batch database error", job.getErrorMessage());
        verify(telemetryRepository, times(2)).insertBatch(anyList());
    }

    @Test
    void testProcessIngestionEdgeCaseFields(@TempDir Path tempDir) throws IOException, InterruptedException {
        File tempFile = tempDir.resolve("edge_case_telemetry.json").toFile();
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("[\n" +
                    "  {\n" +
                    "    \"id\": \"c3a04294-f2a8-48b4-bf5a-4b953835ee3f\",\n" +
                    "    \"deviceId\": \"DEV-1001\",\n" +
                    "    \"timestamp\": \"2026-05-22T10:00:00\",\n" +
                    "    \"status\": \"OK\",\n" +
                    "    \"metric_abc\": 10.5,\n" + // NumberFormatException
                    "    \"metric_0\": 20.5,\n" + // Out of bounds min
                    "    \"metric_147\": 30.5,\n" + // Out of bounds max
                    "    \"unknown_field\": { \"nested\": \"ignored\" }\n" + // Calls parser.skipChildren()
                    "  }\n" +
                    "]");
        }

        IngestionService.IngestionJob job = ingestionService.createJob(1, 0.01, "postgres", "PARALLEL_VT");
        ingestionService.startIngestion(tempFile.getAbsolutePath(), job.getId());

        awaitJobCompletion(job);

        assertEquals("COMPLETED", job.getStatus());
        assertEquals(1, job.getRecordsRead().get());
        assertEquals(1, job.getRecordsWritten().get());
        verify(telemetryRepository).insertBatch(anyList());
    }

    private void awaitJobCompletion(IngestionService.IngestionJob job) throws InterruptedException {
        long limit = System.currentTimeMillis() + 5000;
        while (!"COMPLETED".equals(job.getStatus()) && !"FAILED".equals(job.getStatus())) {
            if (System.currentTimeMillis() > limit) {
                fail("Job timed out waiting for status transition");
            }
            Thread.sleep(50);
        }
    }
}
