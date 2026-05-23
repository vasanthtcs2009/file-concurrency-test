package com.example.file_concurrency_test.repository;

import com.example.file_concurrency_test.model.TelemetryRecord;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
public class TelemetryRepository {

    private final JdbcTemplate jdbcTemplate;
    private String insertSql;

    public TelemetryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        StringBuilder sql = new StringBuilder("INSERT INTO telemetry_records (id, device_id, timestamp, status");
        for (int i = 1; i <= 146; i++) {
            sql.append(", metric_").append(i);
        }
        sql.append(") VALUES (?, ?, ?, ?");
        for (int i = 1; i <= 146; i++) {
            sql.append(", ?");
        }
        sql.append(")");
        this.insertSql = sql.toString();
    }

    @Transactional
    public void insertBatch(List<TelemetryRecord> records) {
        jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TelemetryRecord record = records.get(i);
                ps.setObject(1, record.getId());
                ps.setString(2, record.getDeviceId());
                ps.setTimestamp(3, Timestamp.valueOf(record.getTimestamp()));
                ps.setString(4, record.getStatus());
                
                double[] metrics = record.getMetrics();
                for (int j = 0; j < 146; j++) {
                    ps.setDouble(5 + j, metrics[j]);
                }
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });
    }

    public long count() {
        Long val = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM telemetry_records", Long.class);
        return val != null ? val : 0L;
    }

    public List<Map<String, Object>> getSample(int limit) {
        return jdbcTemplate.queryForList("SELECT * FROM telemetry_records LIMIT " + limit);
    }

    @Transactional
    public void truncateTable() {
        jdbcTemplate.execute("TRUNCATE TABLE telemetry_records");
    }
}
