package com.smartdev.hub.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;

/**
 * Exercise 1 — Talk to Mistral via Ollama's REST API.
 *
 * What you'll learn:
 *   - How the chat completion request/response format works
 *   - How to send a system prompt + user message
 *   - How to parse the JSON response with Jackson
 *
 * Run main() directly — no Spring Boot needed for agent exercises.
 */
public class OllamaClient {

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "mistral";

    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Core method: send a single message, get a reply
    // -------------------------------------------------------------------------

    public String chat(String systemPrompt, String userMessage) throws IOException {

        // Build the JSON request body:
        // {
        //   "model": "mistral",
        //   "stream": false,
        //   "messages": [
        //     { "role": "system", "content": "..." },
        //     { "role": "user",   "content": "..." }
        //   ]
        // }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");

        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        // Send the HTTP POST
        Request request = new Request.Builder()
                .url(OLLAMA_URL)
                .post(RequestBody.create(
                        mapper.writeValueAsString(body),
                        MediaType.get("application/json")))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ollama returned HTTP " + response.code());
            }

            // Parse the response:
            // { "message": { "role": "assistant", "content": "..." }, ... }
            String responseBody = response.body().string();
            JsonNode json = mapper.readTree(responseBody);
            return json.path("message").path("content").asText();
        }
    }

    // -------------------------------------------------------------------------
    // Main — run this directly to test the connection
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        OllamaClient client = new OllamaClient();

        System.out.println("=== Exercise 1: Calling Mistral from Java ===\n");

        // Test 1: basic question
        String systemPrompt = """
                You are a senior Java developer assistant working on a Spring Boot project
                called SmartDev Hub. Be concise and practical in your answers.
                """;

        String reply = client.chat(systemPrompt, "What is the purpose of a JPA Repository?");

        System.out.println("Question: What is the purpose of a JPA Repository?");
        System.out.println(MODEL + ": " + reply);
        System.out.println();

        // Test 2: code-aware question
        String reply2 = client.chat(systemPrompt,
                "In one sentence, what does Spring's @Transactional annotation do?");

        System.out.println("Question: What does @Transactional do?");
        System.out.println(MODEL + ": " + reply2);
        System.out.println();

        System.out.println("=== Connection working! Move on to Exercise 2 ===");
    }
}