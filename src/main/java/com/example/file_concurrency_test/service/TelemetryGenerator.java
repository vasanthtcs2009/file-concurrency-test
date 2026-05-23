package com.example.file_concurrency_test.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;

@Service
public class TelemetryGenerator {

    private final Random random = new Random();
    private static final String[] STATUSES = {"OK", "WARNING", "CRITICAL"};

    public void generateJsonFile(String filePath, int recordCount) throws IOException {
        JsonFactory jsonFactory = new JsonFactory();
        try (FileOutputStream fos = new FileOutputStream(filePath);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             JsonGenerator jg = jsonFactory.createGenerator(bos)) {
            
            // For massive performance and minimal file size, we write flat JSON without pretty printing.
            jg.writeStartArray();
            
            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            
            for (int i = 0; i < recordCount; i++) {
                jg.writeStartObject();
                
                jg.writeStringField("id", UUID.randomUUID().toString());
                jg.writeStringField("deviceId", "DEV-" + (1000 + random.nextInt(9000)));
                jg.writeStringField("timestamp", LocalDateTime.now().minusSeconds(recordCount - i).format(formatter));
                jg.writeStringField("status", STATUSES[random.nextInt(STATUSES.length)]);
                
                for (int j = 1; j <= 146; j++) {
                    // Generate double values with 2 decimal places
                    double value = 10.0 + random.nextDouble() * 90.0;
                    jg.writeNumberField("metric_" + j, Math.round(value * 100.0) / 100.0);
                }
                
                jg.writeEndObject();
            }
            
            jg.writeEndArray();
            jg.flush();
        }
    }
}
