# LogAI SDK

🤖 **LLM-powered Java Log Analyzer & Auto-Fix Engine**

Automatically analyze Java application logs, identify recurring issues, explain them in human language, and generate code fixes using AI.

[![](https://jitpack.io/v/Arun-Labana/logai-sdk.svg)](https://jitpack.io/#Arun-Labana/logai-sdk)

---

## 🚀 Quick Start

### Step 1: Add JitPack Repository

Add the JitPack repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

### Step 2: Add Dependency

```xml
<dependency>
    <groupId>com.github.Arun-Labana.logai-sdk</groupId>
    <artifactId>logai-remote</artifactId>
    <version>main-SNAPSHOT</version>
</dependency>
```

### Step 3: Configure Logback

Create or update `src/main/resources/logback.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- Console output -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- LogAI - only appId is required! -->
    <appender name="LOGAI" class="com.logai.remote.RemoteLogAppender">
        <appId>YOUR_APP_ID_FROM_DASHBOARD</appId>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE" />
        <appender-ref ref="LOGAI" />
    </root>
</configuration>
```

**That's it!** Just one line of config - your App ID. The SDK handles everything else.

### Step 4: Get Your Credentials

1. Go to the [LogAI Dashboard](https://logai-frontend-app.vercel.app)
2. Sign up / Log in
3. Create a new application in Settings
4. Copy the **App ID** and configure it in your `logback.xml`

---

## 📦 Modules

| Module | Description |
|--------|-------------|
| `logai-remote` | Remote logging appender - sends logs to cloud |
| `logai-sdk` | Local SQLite-based log storage |
| `logai-core` | Core analysis: stack trace parsing, error clustering |
| `logai-llm` | LLM integration for AI analysis |
| `logai-cli` | Command-line interface |
| `logai-report` | Report generation (Markdown, HTML, JSON) |

---

## 🔧 How It Works

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────┐
│   Your App      │     │  RemoteLogAppender   │     │  Supabase   │
│                 │     │                      │     │  (Cloud DB) │
│ logger.error()  │ ──► │  Intercepts WARN+    │ ──► │  Stores     │
│                 │     │  Batches & sends     │     │  log_entries│
└─────────────────┘     └──────────────────────┘     └─────────────┘
                                                            │
                                                            ▼
                        ┌──────────────────────┐     ┌─────────────┐
                        │  AI Analysis         │ ◄── │  Dashboard  │
                        │  (Gemini LLM)        │     │  Scan/View  │
                        │  - Root cause        │     └─────────────┘
                        │  - Code fix patches  │
                        └──────────────────────┘
```

---

## ✨ Features

- **Zero Code Changes** - Just add dependency and configure logback
- **Async Batching** - Logs are batched to minimize performance impact
- **Smart Filtering** - Only WARN/ERROR logs sent to cloud (configurable)
- **Error Clustering** - Groups similar errors together
- **AI Analysis** - Explains root cause in plain English
- **Multi-File Fixes** - Generates unified diff patches across files
- **GitHub Integration** - Fetches actual source code for accurate fixes

---

## 🌐 Related Projects

| Component | Repository | Live URL |
|-----------|------------|----------|
| **SDK** | [logai-sdk](https://github.com/Arun-Labana/logai-sdk) | [JitPack](https://jitpack.io/#Arun-Labana/logai-sdk) |
| **Backend** | [logai-backend](https://github.com/Arun-Labana/logai-backend) | [Render](https://logai-backend.onrender.com) |
| **Frontend** | [logai-frontend](https://github.com/Arun-Labana/logai-frontend) | [Vercel](https://logai-frontend-app.vercel.app) |
| **Sample App** | [sample-logai-app](https://github.com/Arun-Labana/sample-logai-app) | - |

---

## 📋 Configuration Options

| Property | Description | Default | Required |
|----------|-------------|---------|----------|
| `appId` | Application ID from dashboard | - | **Yes** |
| `threshold` | Minimum log level to send | `WARN` | No |
| `batchSize` | Number of logs per batch | `50` | No |
| `flushIntervalMs` | Max time before flush (ms) | `5000` | No |

> **Note:** `supabaseUrl` and `supabaseKey` are built into the SDK - you don't need to configure them!

---

## 🛠️ Building from Source

```bash
git clone https://github.com/Arun-Labana/logai-sdk.git
cd logai-sdk
mvn clean install
```

---

## 📄 License

MIT License - feel free to use in your projects!

---

## 🤝 Contributing

Contributions welcome! Please open an issue or PR.
