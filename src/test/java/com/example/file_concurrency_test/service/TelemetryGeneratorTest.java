package com.example.file_concurrency_test.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryGeneratorTest {

    private final TelemetryGenerator telemetryGenerator = new TelemetryGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testGenerateJsonFile(@TempDir Path tempDir) throws IOException {
        File tempFile = tempDir.resolve("test_telemetry.json").toFile();
        int recordCount = 5;

        telemetryGenerator.generateJsonFile(tempFile.getAbsolutePath(), recordCount);

        assertTrue(tempFile.exists());
        assertTrue(tempFile.length() > 0);

        JsonNode root = objectMapper.readTree(tempFile);
        assertTrue(root.isArray());
        assertEquals(recordCount, root.size());

        for (int i = 0; i < recordCount; i++) {
            JsonNode record = root.get(i);
            assertTrue(record.isObject());

            // Validate standard fields
            assertTrue(record.has("id"));
            assertDoesNotThrow(() -> UUID.fromString(record.get("id").asText()));

            assertTrue(record.has("deviceId"));
            String deviceId = record.get("deviceId").asText();
            assertTrue(deviceId.startsWith("DEV-"));
            assertEquals(8, deviceId.length()); // DEV- + 4 digits

            assertTrue(record.has("timestamp"));
            assertDoesNotThrow(() -> LocalDateTime.parse(record.get("timestamp").asText(), DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            assertTrue(record.has("status"));
            String status = record.get("status").asText();
            assertTrue("OK".equals(status) || "WARNING".equals(status) || "CRITICAL".equals(status));

            // Validate all 146 metrics fields
            for (int j = 1; j <= 146; j++) {
                String metricName = "metric_" + j;
                assertTrue(record.has(metricName));
                double val = record.get(metricName).asDouble();
                assertTrue(val >= 10.0 && val <= 100.0);
            }
        }
    }
}
