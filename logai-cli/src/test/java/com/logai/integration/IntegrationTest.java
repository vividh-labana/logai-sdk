package com.logai.integration;

import com.logai.core.analysis.CodeContextExtractor;
import com.logai.core.analysis.ErrorClusterer;
import com.logai.core.model.CodeContext;
import com.logai.core.model.ErrorCluster;
import com.logai.core.model.LogEntry;
import com.logai.core.model.LogLevel;
import com.logai.core.model.ParsedStackTrace;
import com.logai.core.parser.StackTraceParser;
import com.logai.report.ReportGenerator;
import com.logai.report.model.ReportData;
import com.logai.sdk.LogStore;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for LogAI end-to-end flow.
 * Tests the complete pipeline from log storage to analysis to report generation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IntegrationTest {

    private static Path tempDir;
    private static Path dbPath;
    private static Path sourceDir;

    @BeforeAll
    static void setup() throws IOException {
        tempDir = Files.createTempDirectory("logai-test");
        dbPath = tempDir.resolve("test.db");
        sourceDir = tempDir.resolve("src/main/java");
        Files.createDirectories(sourceDir.resolve("com/example"));
        
        // Create a sample source file for code context extraction
        String sampleCode = """
            package com.example;
            
            import java.util.List;
            import java.util.Optional;
            
            public class OrderService {
                
                private final OrderRepository repository;
                private final PaymentService paymentService;
                
                public OrderService(OrderRepository repository, PaymentService paymentService) {
                    this.repository = repository;
                    this.paymentService = paymentService;
                }
                
                public Order processOrder(String orderId) {
                    Order order = repository.findById(orderId);
                    if (order == null) {
                        throw new IllegalArgumentException("Order not found: " + orderId);
                    }
                    
                    // This line might throw NullPointerException
                    String customerEmail = order.getCustomer().getEmail();
                    
                    paymentService.charge(order);
                    return order;
                }
                
                public List<Order> getOrdersByCustomer(String customerId) {
                    return repository.findByCustomerId(customerId);
                }
            }
            """;
        Files.writeString(sourceDir.resolve("com/example/OrderService.java"), sampleCode);
    }

    @AfterAll
    static void cleanup() throws IOException {
        // Clean up temp directory
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
        }
    }

    @Test
    @Order(1)
    @DisplayName("Store and retrieve log entries")
    void testLogStorage() {
        try (LogStore store = new LogStore(dbPath.toString())) {
            // Create sample log entries
            List<LogEntry> entries = createSampleLogEntries();
            
            // Store them
            store.storeBatch(entries);
            
            // Query back
            Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
            Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
            List<LogEntry> retrieved = store.queryByTimeRange(start, end);
            
            assertFalse(retrieved.isEmpty(), "Should retrieve stored entries");
            assertTrue(retrieved.size() >= entries.size(), "Should have at least as many entries as stored");
            
            // Query errors only
            List<LogEntry> errors = store.queryErrors(start, end);
            assertFalse(errors.isEmpty(), "Should have error entries");
            assertTrue(errors.stream().allMatch(LogEntry::isError), "All should be errors");
        }
    }

    @Test
    @Order(2)
    @DisplayName("Parse stack traces correctly")
    void testStackTraceParsing() {
        StackTraceParser parser = new StackTraceParser();
        
        String stackTrace = """
            java.lang.NullPointerException: Cannot invoke method on null object
            \tat com.example.OrderService.processOrder(OrderService.java:23)
            \tat com.example.OrderController.createOrder(OrderController.java:45)
            \tat sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
            \tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:897)
            Caused by: java.lang.IllegalStateException: Customer not loaded
            \tat com.example.Order.getCustomer(Order.java:67)
            \tat com.example.OrderService.processOrder(OrderService.java:22)
            """;
        
        ParsedStackTrace parsed = parser.parse(stackTrace);
        
        assertNotNull(parsed, "Should parse stack trace");
        assertEquals("java.lang.NullPointerException", parsed.getExceptionClass());
        assertFalse(parsed.getFrames().isEmpty(), "Should have frames");
        
        // Check first frame
        assertEquals("com.example.OrderService", parsed.getFrames().get(0).getClassName());
        assertEquals("processOrder", parsed.getFrames().get(0).getMethodName());
        assertEquals(23, parsed.getFrames().get(0).getLineNumber());
        
        // Check user frames filtering
        List<com.logai.core.model.StackFrame> userFrames = parsed.getUserFrames();
        assertTrue(userFrames.stream().noneMatch(f -> f.getClassName().startsWith("sun.")));
        assertTrue(userFrames.stream().noneMatch(f -> f.getClassName().startsWith("org.springframework.")));
        
        // Check caused by
        assertNotNull(parsed.getCausedBy(), "Should have caused by");
        assertEquals("java.lang.IllegalStateException", parsed.getCausedBy().getExceptionClass());
    }

    @Test
    @Order(3)
    @DisplayName("Cluster similar errors together")
    void testErrorClustering() {
        ErrorClusterer clusterer = new ErrorClusterer();
        List<LogEntry> entries = createSampleLogEntries();
        
        List<ErrorCluster> clusters = clusterer.cluster(entries);
        
        assertFalse(clusters.isEmpty(), "Should create clusters");
        
        // Should group similar NPEs together
        long npeClusterCount = clusters.stream()
                .filter(c -> c.getExceptionClass() != null && 
                            c.getExceptionClass().contains("NullPointerException"))
                .count();
        assertTrue(npeClusterCount >= 1, "Should have NPE cluster");
        
        // Check cluster properties
        ErrorCluster topCluster = clusters.get(0);
        assertTrue(topCluster.getOccurrenceCount() > 0, "Should have occurrences");
        assertNotNull(topCluster.getFirstSeen(), "Should have first seen");
        assertNotNull(topCluster.getLastSeen(), "Should have last seen");
        assertNotNull(topCluster.getSeverity(), "Should calculate severity");
    }

    @Test
    @Order(4)
    @DisplayName("Extract code context from source files")
    void testCodeContextExtraction() {
        CodeContextExtractor extractor = new CodeContextExtractor(
                List.of(sourceDir.toString())
        );
        
        // Test finding source file by class name
        Optional<Path> sourcePath = extractor.findSourceFile("com.example.OrderService");
        assertTrue(sourcePath.isPresent(), "Should find source file");
        
        // Test extracting context
        Optional<CodeContext> context = extractor.extractContext("com.example.OrderService", 23);
        assertTrue(context.isPresent(), "Should extract context");
        
        CodeContext ctx = context.get();
        assertEquals(23, ctx.getTargetLine());
        assertEquals("OrderService", ctx.getClassName());
        assertNotNull(ctx.getSurroundingLines(), "Should have surrounding lines");
        assertFalse(ctx.getSurroundingLines().isEmpty());
        
        // Check method extraction
        assertNotNull(ctx.getMethodName(), "Should identify method");
        assertNotNull(ctx.getMethodBody(), "Should extract method body");
        assertTrue(ctx.getMethodBody().contains("processOrder"));
    }

    @Test
    @Order(5)
    @DisplayName("Generate reports from clustered errors")
    void testReportGeneration() {
        List<LogEntry> entries = createSampleLogEntries();
        ReportGenerator generator = new ReportGenerator();
        
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        
        ReportData data = generator.generateReportData(entries, start, end);
        
        assertNotNull(data, "Should generate report data");
        assertEquals(entries.size(), data.getTotalLogs());
        assertTrue(data.getErrorLogs() > 0);
        assertTrue(data.getClusterCount() > 0);
        
        // Test Markdown generation
        String markdown = generator.generate(data, ReportGenerator.ReportFormat.MARKDOWN);
        assertNotNull(markdown);
        assertTrue(markdown.contains("LogAI Health Report"));
        assertTrue(markdown.contains("Executive Summary"));
        assertTrue(markdown.contains("Error Clusters"));
        
        // Test HTML generation
        String html = generator.generate(data, ReportGenerator.ReportFormat.HTML);
        assertNotNull(html);
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("LogAI Health Report"));
        
        // Test JSON generation
        String json = generator.generate(data, ReportGenerator.ReportFormat.JSON);
        assertNotNull(json);
        assertTrue(json.contains("\"totalLogs\""));
        assertTrue(json.contains("\"clusters\""));
    }

    @Test
    @Order(6)
    @DisplayName("End-to-end flow: store -> cluster -> report")
    void testEndToEndFlow() throws IOException {
        // 1. Store logs
        try (LogStore store = new LogStore(dbPath.toString())) {
            store.storeBatch(createSampleLogEntries());
            
            // 2. Query errors
            Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
            Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
            List<LogEntry> allLogs = store.queryByTimeRange(start, end);
            
            // 3. Generate report
            ReportGenerator generator = new ReportGenerator();
            ReportData data = generator.generateReportData(allLogs, start, end);
            
            // 4. Write report to file
            Path reportPath = tempDir.resolve("test_report.md");
            generator.write(data, ReportGenerator.ReportFormat.MARKDOWN, reportPath);
            
            assertTrue(Files.exists(reportPath), "Report file should exist");
            String content = Files.readString(reportPath);
            assertTrue(content.length() > 100, "Report should have content");
            assertTrue(content.contains("Error Clusters"));
        }
    }

    @Test
    @Order(7)
    @DisplayName("Message normalization for clustering")
    void testMessageNormalization() {
        ErrorClusterer clusterer = new ErrorClusterer();
        
        // Test UUID replacement
        String msg1 = "Failed to process order a1b2c3d4-e5f6-7890-abcd-ef1234567890";
        String norm1 = clusterer.normalizeMessage(msg1);
        assertTrue(norm1.contains("<UUID>"));
        assertFalse(norm1.contains("a1b2c3d4"));
        
        // Test ID replacement
        String msg2 = "User 12345678 not found";
        String norm2 = clusterer.normalizeMessage(msg2);
        assertTrue(norm2.contains("<ID>"));
        
        // Test timestamp replacement
        String msg3 = "Error at 2024-01-15T10:30:00";
        String norm3 = clusterer.normalizeMessage(msg3);
        assertTrue(norm3.contains("<TIMESTAMP>"));
        
        // Test similarity
        assertTrue(clusterer.areMessagesSimilar(
                "Failed to process order abc-123",
                "Failed to process order xyz-789"
        ));
    }

    private List<LogEntry> createSampleLogEntries() {
        List<LogEntry> entries = new ArrayList<>();
        Instant now = Instant.now();
        
        // NullPointerException errors (similar - should cluster)
        for (int i = 0; i < 5; i++) {
            entries.add(LogEntry.builder()
                    .timestamp(now.minus(i, ChronoUnit.MINUTES))
                    .level(LogLevel.ERROR)
                    .logger("com.example.OrderService")
                    .message("Error processing order " + (1000 + i))
                    .stackTrace("""
                            java.lang.NullPointerException: Cannot invoke method on null object
                            \tat com.example.OrderService.processOrder(OrderService.java:23)
                            \tat com.example.OrderController.createOrder(OrderController.java:45)
                            """)
                    .className("com.example.OrderService")
                    .methodName("processOrder")
                    .fileName("OrderService.java")
                    .lineNumber(23)
                    .build());
        }
        
        // Different exception type
        entries.add(LogEntry.builder()
                .timestamp(now.minus(10, ChronoUnit.MINUTES))
                .level(LogLevel.ERROR)
                .logger("com.example.PaymentService")
                .message("Payment failed for customer 12345")
                .stackTrace("""
                        java.lang.IllegalStateException: Payment gateway unavailable
                        \tat com.example.PaymentService.charge(PaymentService.java:89)
                        \tat com.example.OrderService.processOrder(OrderService.java:27)
                        """)
                .className("com.example.PaymentService")
                .methodName("charge")
                .fileName("PaymentService.java")
                .lineNumber(89)
                .build());
        
        // Info logs
        for (int i = 0; i < 10; i++) {
            entries.add(LogEntry.builder()
                    .timestamp(now.minus(i * 2, ChronoUnit.MINUTES))
                    .level(LogLevel.INFO)
                    .logger("com.example.Application")
                    .message("Processing request " + i)
                    .build());
        }
        
        // Warning
        entries.add(LogEntry.builder()
                .timestamp(now.minus(5, ChronoUnit.MINUTES))
                .level(LogLevel.WARN)
                .logger("com.example.CacheService")
                .message("Cache miss for key: user_profile_123")
                .build());
        
        return entries;
    }
}

