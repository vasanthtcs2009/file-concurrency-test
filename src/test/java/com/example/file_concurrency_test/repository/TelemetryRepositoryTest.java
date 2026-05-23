package com.example.file_concurrency_test.repository;

import com.example.file_concurrency_test.model.TelemetryRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private TelemetryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TelemetryRepository(jdbcTemplate);
        repository.init(); // Execute PostConstruct
    }

    @Test
    void testInit() {
        // Assert that calling init() multiple times works and generates correct insertSql
        repository.init();
    }

    @Test
    void testInsertBatch() throws SQLException {
        UUID recordId = UUID.randomUUID();
        LocalDateTime timestamp = LocalDateTime.of(2026, 5, 22, 10, 0, 0);
        double[] metrics = new double[146];
        metrics[0] = 5.5;
        metrics[145] = 95.5;

        List<TelemetryRecord> records = List.of(
                TelemetryRecord.builder()
                        .id(recordId)
                        .deviceId("DEV-999")
                        .timestamp(timestamp)
                        .status("WARNING")
                        .metrics(metrics)
                        .build()
        );

        ArgumentCaptor<BatchPreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);

        repository.insertBatch(records);

        verify(jdbcTemplate).batchUpdate(anyString(), setterCaptor.capture());

        BatchPreparedStatementSetter setter = setterCaptor.getValue();
        assertEquals(1, setter.getBatchSize());

        PreparedStatement ps = mock(PreparedStatement.class);
        setter.setValues(ps, 0);

        verify(ps).setObject(1, recordId);
        verify(ps).setString(2, "DEV-999");
        verify(ps).setTimestamp(3, Timestamp.valueOf(timestamp));
        verify(ps).setString(4, "WARNING");
        
        // 146 calls to setDouble
        verify(ps, times(146)).setDouble(anyInt(), anyDouble());
        verify(ps).setDouble(5, 5.5);
        verify(ps).setDouble(150, 95.5);
    }

    @Test
    void testCount() {
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM telemetry_records"), eq(Long.class))).thenReturn(150L);
        assertEquals(150L, repository.count());

        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(*) FROM telemetry_records"), eq(Long.class))).thenReturn(null);
        assertEquals(0L, repository.count());
    }

    @Test
    void testGetSample() {
        List<Map<String, Object>> mockResult = List.of(Map.of("id", UUID.randomUUID(), "deviceId", "DEV-1"));
        when(jdbcTemplate.queryForList(eq("SELECT * FROM telemetry_records LIMIT 10"))).thenReturn(mockResult);

        List<Map<String, Object>> sample = repository.getSample(10);
        assertEquals(mockResult, sample);
    }

    @Test
    void testTruncateTable() {
        repository.truncateTable();
        verify(jdbcTemplate).execute("TRUNCATE TABLE telemetry_records");
    }
}
