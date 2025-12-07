package com.logai.cli.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration management for LogAI CLI.
 */
public class LogAIConfig {

    private static final String CONFIG_DIR = ".logai";
    private static final String CONFIG_FILE = "config.yaml";
    private static final Path DEFAULT_CONFIG_PATH = Paths.get(
            System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE
    );

    private String openaiApiKey;
    private String openaiModel = "gpt-4o";
    private String databasePath = "logai.db";
    private List<String> sourcePaths = new ArrayList<>();
    private int contextLines = 10;
    private boolean verbose = false;

    public LogAIConfig() {
        // Initialize with defaults
        sourcePaths.add("./src/main/java");
    }

    /**
     * Load configuration from the default location.
     */
    public static LogAIConfig load() {
        return load(DEFAULT_CONFIG_PATH);
    }

    /**
     * Load configuration from a specific path.
     */
    public static LogAIConfig load(Path configPath) {
        LogAIConfig config = new LogAIConfig();
        
        if (!Files.exists(configPath)) {
            // Try to load from environment variables
            config.loadFromEnvironment();
            return config;
        }

        try (InputStream input = Files.newInputStream(configPath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(input);
            
            if (data != null) {
                config.parseYaml(data);
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not load config file: " + e.getMessage());
        }

        // Override with environment variables
        config.loadFromEnvironment();
        
        return config;
    }

    /**
     * Save configuration to the default location.
     */
    public void save() throws IOException {
        save(DEFAULT_CONFIG_PATH);
    }

    /**
     * Save configuration to a specific path.
     */
    public void save(Path configPath) throws IOException {
        // Ensure directory exists
        Files.createDirectories(configPath.getParent());

        Map<String, Object> data = new HashMap<>();
        
        Map<String, Object> openai = new HashMap<>();
        openai.put("api_key", "${OPENAI_API_KEY}"); // Don't save actual key
        openai.put("model", openaiModel);
        data.put("openai", openai);

        Map<String, Object> database = new HashMap<>();
        database.put("path", databasePath);
        data.put("database", database);

        Map<String, Object> source = new HashMap<>();
        source.put("paths", sourcePaths);
        source.put("context_lines", contextLines);
        data.put("source", source);

        Yaml yaml = new Yaml();
        String yamlString = yaml.dump(data);
        Files.writeString(configPath, yamlString);
    }

    @SuppressWarnings("unchecked")
    private void parseYaml(Map<String, Object> data) {
        // Parse OpenAI config
        Map<String, Object> openai = (Map<String, Object>) data.get("openai");
        if (openai != null) {
            if (openai.containsKey("api_key")) {
                String key = String.valueOf(openai.get("api_key"));
                if (!key.startsWith("${")) {
                    openaiApiKey = key;
                }
            }
            if (openai.containsKey("model")) {
                openaiModel = String.valueOf(openai.get("model"));
            }
        }

        // Parse database config
        Map<String, Object> database = (Map<String, Object>) data.get("database");
        if (database != null && database.containsKey("path")) {
            databasePath = String.valueOf(database.get("path"));
        }

        // Parse source config
        Map<String, Object> source = (Map<String, Object>) data.get("source");
        if (source != null) {
            if (source.containsKey("paths")) {
                Object paths = source.get("paths");
                if (paths instanceof List) {
                    sourcePaths = new ArrayList<>();
                    for (Object p : (List<?>) paths) {
                        sourcePaths.add(String.valueOf(p));
                    }
                }
            }
            if (source.containsKey("context_lines")) {
                contextLines = ((Number) source.get("context_lines")).intValue();
            }
        }
    }

    private void loadFromEnvironment() {
        String envApiKey = System.getenv("OPENAI_API_KEY");
        if (envApiKey != null && !envApiKey.isEmpty()) {
            openaiApiKey = envApiKey;
        }

        String envModel = System.getenv("LOGAI_MODEL");
        if (envModel != null && !envModel.isEmpty()) {
            openaiModel = envModel;
        }

        String envDbPath = System.getenv("LOGAI_DB_PATH");
        if (envDbPath != null && !envDbPath.isEmpty()) {
            databasePath = envDbPath;
        }
    }

    // Getters and setters

    public String getOpenaiApiKey() {
        return openaiApiKey;
    }

    public void setOpenaiApiKey(String openaiApiKey) {
        this.openaiApiKey = openaiApiKey;
    }

    public String getOpenaiModel() {
        return openaiModel;
    }

    public void setOpenaiModel(String openaiModel) {
        this.openaiModel = openaiModel;
    }

    public String getDatabasePath() {
        return databasePath;
    }

    public void setDatabasePath(String databasePath) {
        this.databasePath = databasePath;
    }

    public List<String> getSourcePaths() {
        return sourcePaths;
    }

    public void setSourcePaths(List<String> sourcePaths) {
        this.sourcePaths = sourcePaths;
    }

    public int getContextLines() {
        return contextLines;
    }

    public void setContextLines(int contextLines) {
        this.contextLines = contextLines;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public boolean isOpenAIConfigured() {
        return openaiApiKey != null && !openaiApiKey.isEmpty();
    }

    public static Path getDefaultConfigPath() {
        return DEFAULT_CONFIG_PATH;
    }
}

