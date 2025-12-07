package com.logai.cli.command;

import com.logai.cli.config.LogAIConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * Manage LogAI configuration.
 */
@Command(
        name = "config",
        description = "Manage LogAI configuration"
)
public class ConfigCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Key to get/set (e.g., openai.model)", arity = "0..1")
    private String key;

    @Option(names = {"--set", "-s"}, 
            description = "Set a configuration value (e.g., --set openai.model=gpt-4)")
    private String setValue;

    @Option(names = {"--list", "-l"}, 
            description = "List all configuration values")
    private boolean list;

    @Option(names = {"--init"}, 
            description = "Initialize configuration with defaults")
    private boolean init;

    @Override
    public Integer call() {
        LogAIConfig config = LogAIConfig.load();

        if (init) {
            return initConfig();
        }

        if (list) {
            return listConfig(config);
        }

        if (setValue != null) {
            return setConfigValue(config, setValue);
        }

        if (key != null) {
            return getConfigValue(config, key);
        }

        // Default: show current config
        return listConfig(config);
    }

    private Integer initConfig() {
        LogAIConfig config = new LogAIConfig();
        
        try {
            config.save();
            System.out.println("✅ Configuration initialized at: " + LogAIConfig.getDefaultConfigPath());
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("1. Set your OpenAI API key:");
            System.out.println("   export OPENAI_API_KEY='your-api-key'");
            System.out.println();
            System.out.println("2. Or edit the config file directly:");
            System.out.println("   " + LogAIConfig.getDefaultConfigPath());
            return 0;
        } catch (IOException e) {
            System.err.println("❌ Failed to initialize config: " + e.getMessage());
            return 1;
        }
    }

    private Integer listConfig(LogAIConfig config) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    LogAI Configuration                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        System.out.println("📁 Config File: " + LogAIConfig.getDefaultConfigPath());
        System.out.println();
        
        System.out.println("OpenAI:");
        System.out.printf("  api_key:    %s%n", config.isOpenAIConfigured() ? "****" + maskKey(config.getOpenaiApiKey()) : "(not set)");
        System.out.printf("  model:      %s%n", config.getOpenaiModel());
        System.out.println();
        
        System.out.println("Database:");
        System.out.printf("  path:       %s%n", config.getDatabasePath());
        System.out.println();
        
        System.out.println("Source:");
        System.out.printf("  paths:      %s%n", config.getSourcePaths());
        System.out.printf("  context:    %d lines%n", config.getContextLines());
        System.out.println();
        
        System.out.println("Environment Variables:");
        System.out.printf("  OPENAI_API_KEY:  %s%n", System.getenv("OPENAI_API_KEY") != null ? "(set)" : "(not set)");
        System.out.printf("  LOGAI_MODEL:     %s%n", System.getenv("LOGAI_MODEL") != null ? System.getenv("LOGAI_MODEL") : "(not set)");
        System.out.printf("  LOGAI_DB_PATH:   %s%n", System.getenv("LOGAI_DB_PATH") != null ? System.getenv("LOGAI_DB_PATH") : "(not set)");
        System.out.println();

        return 0;
    }

    private Integer setConfigValue(LogAIConfig config, String keyValue) {
        String[] parts = keyValue.split("=", 2);
        if (parts.length != 2) {
            System.err.println("❌ Invalid format. Use: --set key=value");
            return 1;
        }

        String k = parts[0].trim();
        String v = parts[1].trim();

        switch (k.toLowerCase()) {
            case "openai.api_key", "openai_api_key" -> {
                config.setOpenaiApiKey(v);
                System.out.println("⚠️  For security, consider using OPENAI_API_KEY environment variable instead.");
            }
            case "openai.model", "model" -> config.setOpenaiModel(v);
            case "database.path", "db_path" -> config.setDatabasePath(v);
            case "source.context_lines", "context_lines" -> config.setContextLines(Integer.parseInt(v));
            default -> {
                System.err.println("❌ Unknown configuration key: " + k);
                System.err.println("   Valid keys: openai.model, database.path, source.context_lines");
                return 1;
            }
        }

        try {
            config.save();
            System.out.println("✅ Configuration updated: " + k + " = " + (k.contains("api_key") ? "****" : v));
            return 0;
        } catch (IOException e) {
            System.err.println("❌ Failed to save config: " + e.getMessage());
            return 1;
        }
    }

    private Integer getConfigValue(LogAIConfig config, String key) {
        String value = switch (key.toLowerCase()) {
            case "openai.api_key", "openai_api_key" -> config.isOpenAIConfigured() ? "(configured)" : "(not set)";
            case "openai.model", "model" -> config.getOpenaiModel();
            case "database.path", "db_path" -> config.getDatabasePath();
            case "source.paths" -> String.join(", ", config.getSourcePaths());
            case "source.context_lines", "context_lines" -> String.valueOf(config.getContextLines());
            default -> null;
        };

        if (value == null) {
            System.err.println("❌ Unknown configuration key: " + key);
            return 1;
        }

        System.out.println(value);
        return 0;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "";
        return key.substring(key.length() - 4);
    }
}

