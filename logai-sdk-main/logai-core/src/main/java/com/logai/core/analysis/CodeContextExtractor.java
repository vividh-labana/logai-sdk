package com.logai.core.analysis;

import com.logai.core.model.CodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Extracts code context from Java source files.
 * Finds and reads the relevant method body and surrounding code for error analysis.
 */
public class CodeContextExtractor {

    private static final Logger logger = LoggerFactory.getLogger(CodeContextExtractor.class);
    
    private static final int DEFAULT_CONTEXT_LINES = 10;
    
    // Pattern to match method declarations
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^\\s*(public|private|protected|static|final|abstract|synchronized|native|\\s)*" +
            "\\s*(<[^>]+>\\s*)?" + // Generic type parameters
            "\\s*(\\w+(?:<[^>]+>)?(?:\\[\\])?)\\s+" + // Return type
            "(\\w+)\\s*\\(" // Method name
    );
    
    // Pattern to match class declarations
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "^\\s*(public|private|protected|static|final|abstract|\\s)*" +
            "\\s*(class|interface|enum|record)\\s+(\\w+)"
    );
    
    // Pattern to match import statements
    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?([\\w.]+(?:\\.\\*)?);\\s*$"
    );
    
    // Pattern to match field declarations
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^\\s*(public|private|protected|static|final|volatile|transient|\\s)*" +
            "\\s*(\\w+(?:<[^>]+>)?(?:\\[\\])?)\\s+(\\w+)\\s*[;=]"
    );

    private final List<Path> sourcePaths;
    private final int contextLines;

    public CodeContextExtractor(List<String> sourcePaths) {
        this(sourcePaths, DEFAULT_CONTEXT_LINES);
    }

    public CodeContextExtractor(List<String> sourcePaths, int contextLines) {
        this.sourcePaths = sourcePaths.stream()
                .map(Paths::get)
                .toList();
        this.contextLines = contextLines;
    }

    /**
     * Extract code context for a given class name and line number.
     */
    public Optional<CodeContext> extractContext(String className, int lineNumber) {
        return findSourceFile(className)
                .flatMap(path -> extractContextFromFile(path, lineNumber));
    }

    /**
     * Extract code context for a given file name and line number.
     */
    public Optional<CodeContext> extractContextByFileName(String fileName, int lineNumber) {
        return findSourceFileByName(fileName)
                .flatMap(path -> extractContextFromFile(path, lineNumber));
    }

    /**
     * Find the source file for a given fully qualified class name.
     */
    public Optional<Path> findSourceFile(String className) {
        if (className == null || className.isEmpty()) {
            return Optional.empty();
        }

        // Convert class name to file path
        // com.example.MyClass -> com/example/MyClass.java
        // com.example.MyClass$Inner -> com/example/MyClass.java
        String baseName = className.contains("$") 
                ? className.substring(0, className.indexOf('$'))
                : className;
        String relativePath = baseName.replace('.', '/') + ".java";

        for (Path sourcePath : sourcePaths) {
            Path candidatePath = sourcePath.resolve(relativePath);
            if (Files.exists(candidatePath)) {
                return Optional.of(candidatePath);
            }
        }

        logger.debug("Source file not found for class: {}", className);
        return Optional.empty();
    }

    /**
     * Find source file by simple file name (e.g., "MyClass.java").
     */
    public Optional<Path> findSourceFileByName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return Optional.empty();
        }

        for (Path sourcePath : sourcePaths) {
            try (Stream<Path> walk = Files.walk(sourcePath)) {
                Optional<Path> found = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(fileName))
                        .findFirst();
                if (found.isPresent()) {
                    return found;
                }
            } catch (IOException e) {
                logger.warn("Error searching source path: {}", sourcePath, e);
            }
        }

        logger.debug("Source file not found: {}", fileName);
        return Optional.empty();
    }

    /**
     * Extract code context from a specific file at a given line number.
     */
    public Optional<CodeContext> extractContextFromFile(Path filePath, int lineNumber) {
        try {
            List<String> allLines = Files.readAllLines(filePath);
            
            if (lineNumber < 1 || lineNumber > allLines.size()) {
                logger.warn("Line number {} out of range for file {} (total lines: {})", 
                        lineNumber, filePath, allLines.size());
                return Optional.empty();
            }

            CodeContext.Builder builder = CodeContext.builder()
                    .filePath(filePath.toString())
                    .targetLine(lineNumber);

            // Extract imports
            List<String> imports = extractImports(allLines);
            builder.imports(imports);

            // Find the enclosing class
            String className = findEnclosingClass(allLines, lineNumber);
            builder.className(className);

            // Find the enclosing method
            MethodBounds methodBounds = findEnclosingMethod(allLines, lineNumber);
            if (methodBounds != null) {
                builder.methodName(methodBounds.methodName);
                builder.methodBody(extractMethodBody(allLines, methodBounds));
            }

            // Extract surrounding lines
            int startLine = Math.max(1, lineNumber - contextLines);
            int endLine = Math.min(allLines.size(), lineNumber + contextLines);
            List<String> surroundingLines = allLines.subList(startLine - 1, endLine);
            builder.surroundingLines(new ArrayList<>(surroundingLines));
            builder.startLine(startLine);
            builder.endLine(endLine);

            // Extract class fields
            List<String> fields = extractClassFields(allLines, className);
            builder.classFields(fields);

            return Optional.of(builder.build());

        } catch (IOException e) {
            logger.error("Error reading source file: {}", filePath, e);
            return Optional.empty();
        }
    }

    /**
     * Extract all import statements from the file.
     */
    private List<String> extractImports(List<String> lines) {
        List<String> imports = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = IMPORT_PATTERN.matcher(line);
            if (matcher.matches()) {
                imports.add(line.trim());
            }
            // Stop looking after we hit the class declaration
            if (CLASS_PATTERN.matcher(line).find()) {
                break;
            }
        }
        return imports;
    }

    /**
     * Find the class name that encloses the given line.
     */
    private String findEnclosingClass(List<String> lines, int lineNumber) {
        String currentClass = null;
        int depth = 0;
        
        for (int i = 0; i < lines.size() && i < lineNumber; i++) {
            String line = lines.get(i);
            Matcher classMatcher = CLASS_PATTERN.matcher(line);
            
            if (classMatcher.find()) {
                currentClass = classMatcher.group(3);
            }
        }
        
        return currentClass;
    }

    /**
     * Find the method that encloses the given line number.
     */
    private MethodBounds findEnclosingMethod(List<String> lines, int lineNumber) {
        // Search backwards from the target line to find method start
        int methodStartLine = -1;
        String methodName = null;
        int braceCount = 0;
        boolean inMethod = false;

        // First, find the method declaration
        for (int i = lineNumber - 1; i >= 0; i--) {
            String line = lines.get(i);
            
            // Count braces to track scope
            for (char c : line.toCharArray()) {
                if (c == '}') braceCount++;
                else if (c == '{') braceCount--;
            }

            Matcher methodMatcher = METHOD_PATTERN.matcher(line);
            if (methodMatcher.find()) {
                // Check if we're inside this method's scope
                if (braceCount <= 0) {
                    methodStartLine = i + 1;
                    methodName = methodMatcher.group(4);
                    inMethod = true;
                    break;
                }
            }
        }

        if (!inMethod || methodStartLine < 0) {
            return null;
        }

        // Find method end by counting braces
        int methodEndLine = -1;
        braceCount = 0;
        boolean foundOpenBrace = false;

        for (int i = methodStartLine - 1; i < lines.size(); i++) {
            String line = lines.get(i);
            
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    foundOpenBrace = true;
                } else if (c == '}') {
                    braceCount--;
                }
            }

            if (foundOpenBrace && braceCount == 0) {
                methodEndLine = i + 1;
                break;
            }
        }

        if (methodEndLine < 0) {
            methodEndLine = lines.size();
        }

        return new MethodBounds(methodName, methodStartLine, methodEndLine);
    }

    /**
     * Extract the full method body.
     */
    private String extractMethodBody(List<String> lines, MethodBounds bounds) {
        if (bounds == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = bounds.startLine - 1; i < bounds.endLine && i < lines.size(); i++) {
            sb.append(lines.get(i)).append("\n");
        }
        return sb.toString();
    }

    /**
     * Extract field declarations from a class.
     */
    private List<String> extractClassFields(List<String> lines, String className) {
        List<String> fields = new ArrayList<>();
        boolean inClass = false;
        int braceCount = 0;

        for (String line : lines) {
            // Check for class declaration
            Matcher classMatcher = CLASS_PATTERN.matcher(line);
            if (classMatcher.find() && classMatcher.group(3).equals(className)) {
                inClass = true;
            }

            if (inClass) {
                // Count braces
                for (char c : line.toCharArray()) {
                    if (c == '{') braceCount++;
                    else if (c == '}') braceCount--;
                }

                // Look for field declarations at class level (braceCount == 1)
                if (braceCount == 1) {
                    Matcher fieldMatcher = FIELD_PATTERN.matcher(line);
                    if (fieldMatcher.find()) {
                        fields.add(line.trim());
                    }
                }

                // Exit when we leave the class
                if (braceCount == 0 && inClass) {
                    break;
                }
            }
        }

        return fields;
    }

    /**
     * Internal class to hold method boundary information.
     */
    private static class MethodBounds {
        final String methodName;
        final int startLine;
        final int endLine;

        MethodBounds(String methodName, int startLine, int endLine) {
            this.methodName = methodName;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }
}

