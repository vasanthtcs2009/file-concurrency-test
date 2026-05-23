package com.example.file_concurrency_test.controller;

import com.example.file_concurrency_test.service.IngestionService;
import com.example.file_concurrency_test.service.TelemetryGenerator;
import com.example.file_concurrency_test.repository.TelemetryRepository;
import com.example.file_concurrency_test.repository.TelemetryRedisRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TelemetryControllerTest {

    private MockMvc mockMvc;

    @Mock
    private IngestionService ingestionService;

    @Mock
    private TelemetryGenerator telemetryGenerator;

    @Mock
    private TelemetryRepository telemetryRepository;

    @Mock
    private TelemetryRedisRepository telemetryRedisRepository;

    private static final String FILE_PATH = "data/telemetry_data.json";
    private File testFile;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new TelemetryController(ingestionService, telemetryGenerator, telemetryRepository, telemetryRedisRepository)
        ).build();
        testFile = new File(FILE_PATH);
    }

    @AfterEach
    void tearDown() {
        if (testFile.exists()) {
            testFile.delete();
        }
    }

    @Test
    void testGenerateDataSuccess() throws Exception {
        doNothing().when(telemetryGenerator).generateJsonFile(anyString(), anyInt());

        mockMvc.perform(post("/api/telemetry/generate")
                        .param("count", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Generated 50 records successfully"));

        verify(telemetryGenerator).generateJsonFile(eq(FILE_PATH), eq(50));
    }

    @Test
    void testGenerateDataFailure() throws Exception {
        doThrow(new IOException("Disk full")).when(telemetryGenerator).generateJsonFile(anyString(), anyInt());

        mockMvc.perform(post("/api/telemetry/generate")
                        .param("count", "50"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Disk full"));
    }

    @Test
    void testTriggerImportFileNotFound() throws Exception {
        if (testFile.exists()) {
            testFile.delete();
        }

        mockMvc.perform(post("/api/telemetry/import"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Mock data file not found. Call /generate first."));
    }

    @Test
    void testTriggerImportSuccess() throws Exception {
        File parent = testFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        testFile.createNewFile();

        IngestionService.IngestionJob mockJob = new IngestionService.IngestionJob();
        mockJob.setId("test-job-id");
        mockJob.setStatus("RUNNING");
        mockJob.setBatchSize(500);
        mockJob.setFileSizeMb(0.5);

        when(ingestionService.createJob(anyInt(), anyDouble(), anyString(), anyString())).thenReturn(mockJob);
        doNothing().when(ingestionService).startIngestion(anyString(), anyString());

        mockMvc.perform(post("/api/telemetry/import")
                        .param("batchSize", "500")
                        .param("target", "both")
                        .param("redisStrategy", "PARALLEL_VT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("test-job-id"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.batchSize").value(500))
                .andExpect(jsonPath("$.target").value("both"))
                .andExpect(jsonPath("$.redisStrategy").value("PARALLEL_VT"));

        verify(ingestionService).createJob(eq(500), anyDouble(), eq("both"), eq("PARALLEL_VT"));
        verify(ingestionService).startIngestion(eq(FILE_PATH), eq("test-job-id"));
    }

    @Test
    void testGetJobStatusNotFound() throws Exception {
        when(ingestionService.getJob("invalid-id")).thenReturn(null);

        mockMvc.perform(get("/api/telemetry/status/invalid-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetJobStatusFound() throws Exception {
        IngestionService.IngestionJob mockJob = new IngestionService.IngestionJob();
        mockJob.setId("job-123");
        mockJob.setStatus("RUNNING");
        mockJob.setBatchSize(100);
        mockJob.setFileSizeMb(1.2);
        mockJob.setStartTime(System.currentTimeMillis() - 1000);

        when(ingestionService.getJob("job-123")).thenReturn(mockJob);
        when(telemetryRepository.count()).thenReturn(5000L);
        when(telemetryRedisRepository.count()).thenReturn(2000L);

        mockMvc.perform(get("/api/telemetry/status/job-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.dbRowCount").value(5000))
                .andExpect(jsonPath("$.redisRowCount").value(2000))
                .andExpect(jsonPath("$.target").value("both"))
                .andExpect(jsonPath("$.redisStrategy").value("PARALLEL_VT"));
    }

    @Test
    void testGetAllStatus() throws Exception {
        IngestionService.IngestionJob mockJob = new IngestionService.IngestionJob();
        mockJob.setId("job-123");
        mockJob.setStatus("COMPLETED");
        mockJob.setBatchSize(100);
        mockJob.setFileSizeMb(1.2);
        mockJob.setStartTime(System.currentTimeMillis() - 2000);
        mockJob.setEndTime(System.currentTimeMillis() - 1000);

        when(ingestionService.getJobs()).thenReturn(List.of(mockJob));
        when(telemetryRepository.count()).thenReturn(10000L);
        when(telemetryRedisRepository.count()).thenReturn(4000L);

        mockMvc.perform(get("/api/telemetry/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbRowCount").value(10000))
                .andExpect(jsonPath("$.redisRowCount").value(4000))
                .andExpect(jsonPath("$.jobs[0].jobId").value("job-123"))
                .andExpect(jsonPath("$.jobs[0].status").value("COMPLETED"));
    }

    @Test
    void testGetSample() throws Exception {
        List<Map<String, Object>> mockSample = List.of(Map.of("id", "uuid", "deviceId", "DEV-1"));
        when(telemetryRepository.getSample(100)).thenReturn(mockSample);

        mockMvc.perform(get("/api/telemetry/sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value("DEV-1"));
    }

    @Test
    void testClearAll() throws Exception {
        File parent = testFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        testFile.createNewFile();

        doNothing().when(telemetryRepository).truncateTable();
        doNothing().when(telemetryRedisRepository).flushAll();

        mockMvc.perform(delete("/api/telemetry/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Database truncated successfully"))
                .andExpect(jsonPath("$.fileDeleted").value(true));

        assertFalse(testFile.exists());
        verify(telemetryRepository).truncateTable();
        verify(telemetryRedisRepository).flushAll();
    }

    @Test
    void testGenerateDataCreateParentDirectory() throws Exception {
        File dataDir = new File("data");
        if (dataDir.exists()) {
            // Delete data directory to force parentDir.mkdirs() to run
            File[] files = dataDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dataDir.delete();
        }

        doNothing().when(telemetryGenerator).generateJsonFile(anyString(), anyInt());

        mockMvc.perform(post("/api/telemetry/generate")
                        .param("count", "50"))
                .andExpect(status().isOk());

        verify(telemetryGenerator).generateJsonFile(eq(FILE_PATH), eq(50));
        assertTrue(dataDir.exists());
    }

    @Test
    void testClearAllFileNotFound() throws Exception {
        if (testFile.exists()) {
            testFile.delete();
        }

        doNothing().when(telemetryRepository).truncateTable();
        doNothing().when(telemetryRedisRepository).flushAll();

        mockMvc.perform(delete("/api/telemetry/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Database truncated successfully"))
                .andExpect(jsonPath("$.fileDeleted").value(false));

        verify(telemetryRepository).truncateTable();
        verify(telemetryRedisRepository).flushAll();
    }

    @Test
    void testJobStatusZeroElapsedTime() throws Exception {
        IngestionService.IngestionJob mockJob = new IngestionService.IngestionJob();
        mockJob.setId("job-zero-time");
        mockJob.setStatus("COMPLETED");
        mockJob.setBatchSize(100);
        mockJob.setFileSizeMb(1.2);
        // Start time and end time are equal
        mockJob.setStartTime(1000);
        mockJob.setEndTime(1000);

        when(ingestionService.getJob("job-zero-time")).thenReturn(mockJob);
        when(telemetryRepository.count()).thenReturn(5000L);
        when(telemetryRedisRepository.count()).thenReturn(0L);

        mockMvc.perform(get("/api/telemetry/status/job-zero-time"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-zero-time"))
                .andExpect(jsonPath("$.throughputRecordsPerSec").value(0.0));
    }
}
