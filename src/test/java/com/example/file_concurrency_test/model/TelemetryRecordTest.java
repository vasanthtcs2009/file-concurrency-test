package com.example.file_concurrency_test.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryRecordTest {

    @Test
    void testTelemetryRecordGettersAndSetters() {
        TelemetryRecord record = new TelemetryRecord();
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        double[] metrics = new double[146];
        metrics[0] = 12.34;

        record.setId(id);
        record.setDeviceId("DEV-1234");
        record.setTimestamp(now);
        record.setStatus("OK");
        record.setMetrics(metrics);

        assertEquals(id, record.getId());
        assertEquals("DEV-1234", record.getDeviceId());
        assertEquals(now, record.getTimestamp());
        assertEquals("OK", record.getStatus());
        assertArrayEquals(metrics, record.getMetrics());
    }

    @Test
    void testTelemetryRecordBuilder() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        double[] metrics = new double[146];
        metrics[5] = 99.9;

        TelemetryRecord record = TelemetryRecord.builder()
                .id(id)
                .deviceId("DEV-5678")
                .timestamp(now)
                .status("WARNING")
                .metrics(metrics)
                .build();

        assertEquals(id, record.getId());
        assertEquals("DEV-5678", record.getDeviceId());
        assertEquals(now, record.getTimestamp());
        assertEquals("WARNING", record.getStatus());
        assertArrayEquals(metrics, record.getMetrics());
    }

    @Test
    void testTelemetryRecordEqualsAndHashCode() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        double[] metrics1 = new double[146];
        metrics1[0] = 1.0;
        double[] metrics2 = new double[146];
        metrics2[0] = 1.0;

        TelemetryRecord record1 = new TelemetryRecord(id, "DEV-1", now, "OK", metrics1);
        TelemetryRecord record2 = new TelemetryRecord(id, "DEV-1", now, "OK", metrics2);
        TelemetryRecord record3 = new TelemetryRecord(UUID.randomUUID(), "DEV-2", now, "CRITICAL", new double[146]);

        assertEquals(record1, record2);
        assertNotEquals(record1, record3);
        assertNotEquals(record1, null);
        assertNotEquals(record1, new Object());

        assertEquals(record1.hashCode(), record2.hashCode());
        assertNotEquals(record1.hashCode(), record3.hashCode());
    }

    @Test
    void testTelemetryRecordToString() {
        TelemetryRecord record = TelemetryRecord.builder()
                .deviceId("DEV-1")
                .status("OK")
                .build();

        String str = record.toString();
        assertTrue(str.contains("deviceId=DEV-1"));
        assertTrue(str.contains("status=OK"));
    }

    @Test
    void testBuilderDefault() {
        TelemetryRecord record = new TelemetryRecord();
        assertNotNull(record.getMetrics());
        assertEquals(146, record.getMetrics().length);

        TelemetryRecord recordBuilder = TelemetryRecord.builder().build();
        assertNotNull(recordBuilder.getMetrics());
        assertEquals(146, recordBuilder.getMetrics().length);
    }
}
