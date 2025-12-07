package com.logai.report;

import com.logai.report.model.ReportData;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Interface for report writers.
 */
public interface ReportWriter {

    /**
     * Generate a report as a string.
     */
    String generate(ReportData data);

    /**
     * Write a report to a file.
     */
    void write(ReportData data, Path outputPath) throws IOException;

    /**
     * Get the file extension for this report type.
     */
    String getFileExtension();
}

