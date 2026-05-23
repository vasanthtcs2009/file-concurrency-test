package com.example.file_concurrency_test.repository;

import com.example.file_concurrency_test.model.TelemetryRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Repository
@Slf4j
public class TelemetryRedisRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TelemetryRedisRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static final String KEY_PREFIX = "telemetry:record:";

    public void insertParallel(List<TelemetryRecord> records) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (TelemetryRecord record : records) {
                executor.submit(() -> {
                    try {
                        String json = objectMapper.writeValueAsString(record);
                        redisTemplate.opsForValue().set(KEY_PREFIX + record.getId(), json);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to serialize telemetry record", e);
                    }
                });
            }
            executor.shutdown();
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                log.warn("Redis parallel insertion executor timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Redis parallel insertion interrupted", e);
        }
    }

    public void insertPipeline(List<TelemetryRecord> records) {
        redisTemplate.executePipelined((RedisConnection connection) -> {
            for (TelemetryRecord record : records) {
                try {
                    byte[] key = (KEY_PREFIX + record.getId()).getBytes();
                    byte[] value = objectMapper.writeValueAsBytes(record);
                    connection.stringCommands().set(key, value);
                } catch (JsonProcessingException e) {
                    log.error("Failed to serialize telemetry record for pipelining", e);
                }
            }
            return null;
        });
    }

    public long count() {
        String countStr = redisTemplate.opsForValue().get("telemetry:count");
        return countStr != null ? Long.parseLong(countStr) : 0L;
    }

    public void incrementCount(long delta) {
        redisTemplate.opsForValue().increment("telemetry:count", delta);
    }

    public void flushAll() {
        redisTemplate.opsForValue().set("telemetry:count", "0");
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        } catch (Exception e) {
            log.error("Failed to flush Redis database", e);
        }
    }
}
