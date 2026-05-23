package com.example.file_concurrency_test.model;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryRecord {
    private UUID id;
    private String deviceId;
    private LocalDateTime timestamp;
    private String status;
    @Builder.Default
    private double[] metrics = new double[146];
}
