package com.logai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP client for OpenAI API.
 * Uses Java 11's HttpClient for making requests to the OpenAI chat completions API.
 */
public class OpenAIClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenAIClient.class);
    
    private static final String DEFAULT_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAIClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public OpenAIClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_API_URL, DEFAULT_MAX_TOKENS);
    }

    public OpenAIClient(String apiKey, String model, String apiUrl, int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = apiUrl;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Send a chat completion request to OpenAI.
     * 
     * @param systemPrompt The system prompt to set context
     * @param userPrompt The user prompt with the actual request
     * @return The assistant's response text
     */
    public Optional<String> chat(String systemPrompt, String userPrompt) {
        try {
            ObjectNode requestBody = buildRequestBody(systemPrompt, userPrompt);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .timeout(DEFAULT_TIMEOUT)
                    .build();

            logger.debug("Sending request to OpenAI API");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("OpenAI API error: {} - {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            return parseResponse(response.body());

        } catch (IOException e) {
            logger.error("IO error communicating with OpenAI API", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            logger.error("Request to OpenAI API was interrupted", e);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Send a simple completion request with just a user prompt.
     */
    public Optional<String> complete(String prompt) {
        return chat(null, prompt);
    }

    private ObjectNode buildRequestBody(String systemPrompt, String userPrompt) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", 0.2); // Lower temperature for more consistent outputs

        ArrayNode messages = objectMapper.createArrayNode();

        // System message
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            ObjectNode systemMessage = objectMapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }

        // User message
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);
        messages.add(userMessage);

        root.set("messages", messages);
        return root;
    }

    private Optional<String> parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                logger.warn("No choices in OpenAI response");
                return Optional.empty();
            }

            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.get("message");
            if (message == null) {
                logger.warn("No message in OpenAI response choice");
                return Optional.empty();
            }

            JsonNode content = message.get("content");
            if (content == null) {
                logger.warn("No content in OpenAI response message");
                return Optional.empty();
            }

            String responseText = content.asText();
            logger.debug("Received response from OpenAI ({} chars)", responseText.length());
            return Optional.of(responseText);

        } catch (Exception e) {
            logger.error("Failed to parse OpenAI response", e);
            return Optional.empty();
        }
    }

    /**
     * Check if the API key is configured.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty() && !apiKey.equals("${OPENAI_API_KEY}");
    }

    /**
     * Get the configured model name.
     */
    public String getModel() {
        return model;
    }
}

