package com.logai.cli;

import com.logai.cli.command.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main CLI entry point for LogAI.
 */
@Command(
        name = "logai",
        description = "LLM-powered log analyzer and auto-fix engine",
        version = "1.0.0",
        mixinStandardHelpOptions = true,
        subcommands = {
                ScanCommand.class,
                ClustersCommand.class,
                FixCommand.class,
                ReportCommand.class,
                ConfigCommand.class
        }
)
public class LogAICli implements Runnable {

    @Override
    public void run() {
        // If no subcommand is specified, print help
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new LogAICli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
        System.exit(exitCode);
    }
}

