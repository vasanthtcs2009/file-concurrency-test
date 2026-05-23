package com.example.file_concurrency_test.repository;

import com.example.file_concurrency_test.model.TelemetryRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryRedisRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection redisConnection;

    @Mock
    private RedisServerCommands serverCommands;

    @Mock
    private RedisStringCommands stringCommands;

    private TelemetryRedisRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TelemetryRedisRepository(redisTemplate);
    }

    @Test
    void testInsertParallel() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        UUID recordId = UUID.randomUUID();
        TelemetryRecord record = TelemetryRecord.builder()
                .id(recordId)
                .deviceId("DEV-100")
                .timestamp(LocalDateTime.now())
                .status("OK")
                .build();

        repository.insertParallel(List.of(record));

        verify(valueOperations, timeout(1000)).set(eq("telemetry:record:" + recordId), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testInsertPipeline() {
        UUID recordId = UUID.randomUUID();
        TelemetryRecord record = TelemetryRecord.builder()
                .id(recordId)
                .deviceId("DEV-100")
                .timestamp(LocalDateTime.now())
                .status("OK")
                .build();

        when(redisConnection.stringCommands()).thenReturn(stringCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class)))
                .thenAnswer(invocation -> {
                    RedisCallback<?> callback = invocation.getArgument(0);
                    return callback.doInRedis(redisConnection);
                });

        repository.insertPipeline(List.of(record));

        verify(stringCommands).set(eq(("telemetry:record:" + recordId).getBytes()), any(byte[].class));
    }

    @Test
    void testCount() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("telemetry:count")).thenReturn("42");

        assertEquals(42L, repository.count());

        when(valueOperations.get("telemetry:count")).thenReturn(null);
        assertEquals(0L, repository.count());
    }

    @Test
    void testIncrementCount() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        repository.incrementCount(10L);

        verify(valueOperations).increment("telemetry:count", 10L);
    }

    @Test
    void testFlushAll() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.serverCommands()).thenReturn(serverCommands);

        repository.flushAll();

        verify(valueOperations).set("telemetry:count", "0");
        verify(serverCommands).flushDb();
        verify(redisConnection).close();
    }
}
