package com.logai.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.logai.report.model.ReportData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates JSON format reports for programmatic consumption.
 */
public class JsonReportWriter implements ReportWriter {

    private final ObjectMapper objectMapper;

    public JsonReportWriter() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public String generate(ReportData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize report to JSON", e);
        }
    }

    @Override
    public void write(ReportData data, Path outputPath) throws IOException {
        String content = generate(data);
        Files.writeString(outputPath, content);
    }

    @Override
    public String getFileExtension() {
        return "json";
    }
}

