package com.example.file_concurrency_test.controller;

import com.example.file_concurrency_test.service.IngestionService;
import com.example.file_concurrency_test.service.TelemetryGenerator;
import com.example.file_concurrency_test.repository.TelemetryRepository;
import com.example.file_concurrency_test.repository.TelemetryRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/telemetry")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin
public class TelemetryController {

    private final IngestionService ingestionService;
    private final TelemetryGenerator telemetryGenerator;
    private final TelemetryRepository telemetryRepository;
    private final TelemetryRedisRepository telemetryRedisRepository;

    private static final String FILE_PATH = "data/telemetry_data.json";

    @PostMapping("/generate")
    public ResponseEntity<?> generateData(@RequestParam(defaultValue = "50000") int count) {
        try {
            File file = new File(FILE_PATH);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            long start = System.currentTimeMillis();
            telemetryGenerator.generateJsonFile(FILE_PATH, count);
            long duration = System.currentTimeMillis() - start;

            long size = file.length();
            double sizeMb = size / (1024.0 * 1024.0);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Generated " + count + " records successfully");
            response.put("filePath", file.getAbsolutePath());
            response.put("fileSizeMb", Math.round(sizeMb * 100.0) / 100.0);
            response.put("durationMs", duration);

            return ResponseEntity.ok(response);
        } catch (IOException e) {
            log.error("Failed to generate test file", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/import")
    public ResponseEntity<?> triggerImport(
            @RequestParam(defaultValue = "1000") int batchSize,
            @RequestParam(defaultValue = "both") String target,
            @RequestParam(defaultValue = "PARALLEL_VT") String redisStrategy) {
        File file = new File(FILE_PATH);
        if (!file.exists()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Mock data file not found. Call /generate first."));
        }

        double sizeMb = file.length() / (1024.0 * 1024.0);
        IngestionService.IngestionJob job = ingestionService.createJob(
                batchSize,
                Math.round(sizeMb * 100.0) / 100.0,
                target,
                redisStrategy);
        ingestionService.startIngestion(FILE_PATH, job.getId());

        return ResponseEntity.ok(Map.of(
                "jobId", job.getId(),
                "status", job.getStatus(),
                "batchSize", job.getBatchSize(),
                "fileSizeMb", job.getFileSizeMb(),
                "target", job.getTarget(),
                "redisStrategy", job.getRedisStrategy()));
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
        IngestionService.IngestionJob job = ingestionService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        long dbCount = telemetryRepository.count();
        long redisCount = telemetryRedisRepository.count();
        Map<String, Object> response = buildJobResponse(job, dbCount, redisCount);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    public ResponseEntity<?> getAllStatus() {
        List<IngestionService.IngestionJob> activeJobs = ingestionService.getJobs();
        long dbCount = telemetryRepository.count();
        long redisCount = telemetryRedisRepository.count();

        Map<String, Object> response = new HashMap<>();
        response.put("dbRowCount", dbCount);
        response.put("redisRowCount", redisCount);
        response.put("jobs", activeJobs.stream().map(job -> buildJobResponse(job, dbCount, redisCount)).toList());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/sample")
    public ResponseEntity<?> getSample() {
        return ResponseEntity.ok(telemetryRepository.getSample(100));
    }

    @DeleteMapping("/clear")
    public ResponseEntity<?> clearAll() {
        telemetryRepository.truncateTable();
        telemetryRedisRepository.flushAll();
        File file = new File(FILE_PATH);
        boolean deleted = false;
        if (file.exists()) {
            deleted = file.delete();
        }
        return ResponseEntity.ok(Map.of(
                "message", "Database truncated successfully",
                "fileDeleted", deleted));
    }

    private Map<String, Object> buildJobResponse(IngestionService.IngestionJob job, long dbCount, long redisCount) {
        Map<String, Object> response = new HashMap<>();
        response.put("jobId", job.getId());
        response.put("status", job.getStatus());
        response.put("recordsRead", job.getRecordsRead().get());
        response.put("recordsWritten", job.getRecordsWritten().get());
        response.put("activeWriteThreads", job.getActiveWriteThreads().get());
        response.put("batchSize", job.getBatchSize());
        response.put("fileSizeMb", job.getFileSizeMb());
        response.put("errorMessage", job.getErrorMessage());
        response.put("target", job.getTarget());
        response.put("redisStrategy", job.getRedisStrategy());
        response.put("redisWriteTimeMs", job.getRedisWriteTimeMs().get());

        long startTime = job.getStartTime();
        long endTime = job.getStatus().equals("RUNNING") ? System.currentTimeMillis() : job.getEndTime();
        long elapsedMs = endTime - startTime;
        response.put("elapsedTimeMs", elapsedMs);

        long written = job.getRecordsWritten().get();
        double throughput = 0;
        if (elapsedMs > 0) {
            throughput = (written / (elapsedMs / 1000.0));
        }
        response.put("throughputRecordsPerSec", Math.round(throughput * 100.0) / 100.0);
        response.put("dbRowCount", dbCount);
        response.put("redisRowCount", redisCount);
        response.put("writeTimeMs", job.getWriteTimeMs().get());

        return response;
    }
}
