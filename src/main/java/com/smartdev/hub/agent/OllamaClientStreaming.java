package com.smartdev.hub.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Exercise 2 — Streaming responses from Mistral.
 *
 * What you'll learn:
 *   - The difference between stream=false (wait for full reply) and stream=true (tokens arrive live)
 *   - How Ollama sends NDJSON — one JSON object per line, as tokens are generated
 *   - How to read a chunked HTTP response line by line in Java
 *   - How to detect the final chunk (done=true) and stop reading
 *
 * Compare with Exercise 1:
 *   - Exercise 1: one big HTTP response, you wait, then print everything
 *   - Exercise 2: tiny chunks arrive continuously, you print each token immediately
 *
 * This is how ChatGPT's "typing" effect works.
 */
public class OllamaClientStreaming {

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "mistral";

    private final OkHttpClient http = new OkHttpClient.Builder()
            // Disable read timeout — streaming can take a while
            .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Stream a response — prints each token to stdout as it arrives
    // Returns the full assembled response when done
    // -------------------------------------------------------------------------

    public String chatStreaming(String systemPrompt, String userMessage) throws IOException {

        // Same request body as Exercise 1, but stream=true
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("stream", true);          // <-- this is the only change from Exercise 1

        ArrayNode messages = body.putArray("messages");

        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        Request request = new Request.Builder()
                .url(OLLAMA_URL)
                .post(RequestBody.create(
                        mapper.writeValueAsString(body),
                        MediaType.get("application/json")))
                .build();

        StringBuilder fullResponse = new StringBuilder();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ollama returned HTTP " + response.code());
            }

            // With stream=true, Ollama sends NDJSON:
            // Each line is a complete JSON object like:
            //   {"model":"mistral","message":{"role":"assistant","content":"The"},"done":false}
            //   {"model":"mistral","message":{"role":"assistant","content":" purpose"},"done":false}
            //   {"model":"mistral","message":{"role":"assistant","content":"."},"done":true}
            //
            // We read line by line, extract the token, print it immediately, stop when done=true

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;

                JsonNode chunk = mapper.readTree(line);

                // Extract the token from this chunk
                String token = chunk.path("message").path("content").asText();

                // Print immediately — no newline, so tokens appear inline
                System.out.print(token);
                System.out.flush();   // important: forces the token to appear right away

                // Accumulate the full response
                fullResponse.append(token);

                // Stop when Ollama signals it's done
                if (chunk.path("done").asBoolean()) {
                    break;
                }
            }
        }

        System.out.println(); // newline after streaming ends
        return fullResponse.toString();
    }

    // -------------------------------------------------------------------------
    // Main — compare streaming vs non-streaming side by side
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException, InterruptedException {
        OllamaClientStreaming client = new OllamaClientStreaming();

        String systemPrompt = """
                You are a senior Java developer assistant working on SmartDev Hub,
                a Spring Boot task tracking platform. Be concise and practical.
                """;

        // --- Demo 1: watch tokens arrive live ---
        System.out.println("=== Exercise 2: Streaming response ===");
        System.out.println("(watch the tokens appear one by one)\n");
        System.out.print("Mistral: ");

        String full = client.chatStreaming(systemPrompt,
                "Explain in 3 bullet points why streaming LLM responses improves user experience.");

        System.out.println("\n--- Full response assembled from chunks ---");
        System.out.println(full);

        // --- Demo 2: longer response so the streaming effect is obvious ---
        System.out.println("\n=== Longer response — streaming effect more visible ===\n");
        System.out.print("Mistral: ");

        client.chatStreaming(systemPrompt,
                "Write a short Java method that reads a file line by line and prints each line. " +
                        "Add a comment explaining what each line does.");

        System.out.println("\n=== Exercise 2 complete ===");
    }
}