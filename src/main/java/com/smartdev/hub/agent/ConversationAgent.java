package com.smartdev.hub.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * Exercise 3 — Multi-turn conversation with memory.
 *
 * What you'll learn:
 *   - Why LLMs are stateless — they remember nothing between calls
 *   - How to give them memory by sending the full conversation history every time
 *   - The message history format: List<Message> with role + content
 *   - How to build an interactive REPL loop (type a message, get a reply, repeat)
 *
 * The key insight:
 *   Exercise 1 sent 2 messages (system + user) and got 1 reply.
 *   This exercise sends ALL previous messages on every call.
 *   The model sees the entire conversation and can refer back to anything said earlier.
 *
 * Try it:
 *   > My name is Itay and I'm building a Spring Boot app called SmartDev Hub
 *   > What's the name of my app?        ← model remembers
 *   > What stack should I use for tests? ← model knows it's Spring Boot
 *   > Summarise what we've discussed     ← model sees the whole history
 */
public class ConversationAgent {

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "mistral";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Message — a single entry in the conversation history
    // -------------------------------------------------------------------------

    public record Message(String role, String content) {}

    // -------------------------------------------------------------------------
    // Conversation — holds the full history and sends it every turn
    // -------------------------------------------------------------------------

    public static class Conversation {

        private final List<Message> history = new ArrayList<>();
        private final String systemPrompt;

        public Conversation(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public void addUserMessage(String content) {
            history.add(new Message("user", content));
        }

        public void addAssistantMessage(String content) {
            history.add(new Message("assistant", content));
        }

        public List<Message> getHistory() {
            return history;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public int getTurnCount() {
            return (int) history.stream().filter(m -> m.role().equals("user")).count();
        }
    }

    // -------------------------------------------------------------------------
    // Send the full conversation history to Ollama, stream the reply
    // -------------------------------------------------------------------------

    public String chat(Conversation conversation) throws IOException {

        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("stream", true);

        ArrayNode messages = body.putArray("messages");

        // Always send the system prompt first
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", conversation.getSystemPrompt());

        // Then send the FULL history — this is what gives the model memory
        // On turn 1: [system, user1]
        // On turn 2: [system, user1, assistant1, user2]
        // On turn 3: [system, user1, assistant1, user2, assistant2, user3]
        for (Message msg : conversation.getHistory()) {
            ObjectNode node = messages.addObject();
            node.put("role", msg.role());
            node.put("content", msg.content());
        }

        Request request = new Request.Builder()
                .url(OLLAMA_URL)
                .post(RequestBody.create(
                        mapper.writeValueAsString(body),
                        MediaType.get("application/json")))
                .build();

        StringBuilder fullReply = new StringBuilder();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ollama returned HTTP " + response.code());
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode chunk = mapper.readTree(line);
                String token = chunk.path("message").path("content").asText();
                System.out.print(token);
                System.out.flush();
                fullReply.append(token);
                if (chunk.path("done").asBoolean()) break;
            }
        }

        System.out.println();
        return fullReply.toString();
    }

    // -------------------------------------------------------------------------
    // Main — interactive REPL, type messages in the terminal
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        ConversationAgent agent = new ConversationAgent();

        // This system prompt gives the model its persona and context
        // Notice it knows about SmartDev Hub — it will use this throughout the chat
        Conversation conversation = new Conversation("""
                You are a senior Java developer assistant working on SmartDev Hub,
                a Spring Boot 3 + SQLite developer productivity platform.
                The project has these modules: Tasks, Bugs, Feature Flags, Activity Log.
                Be concise, practical, and refer back to earlier parts of the conversation
                when relevant. If the user tells you something about themselves or the project,
                remember it and use it in later answers.
                """);

        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Exercise 3: Multi-turn conversation ===");
        System.out.println("Chat with Mistral about your SmartDev Hub project.");
        System.out.println("The model will remember everything you say in this session.");
        System.out.println("Type 'quit' to exit, 'history' to see the conversation log.\n");

        while (true) {
            System.out.print("You [turn " + (conversation.getTurnCount() + 1) + "]: ");
            String input = scanner.nextLine().trim();

            if (input.isBlank()) continue;

            if (input.equalsIgnoreCase("quit")) {
                System.out.println("\nConversation ended. " +
                        conversation.getTurnCount() + " turns, " +
                        conversation.getHistory().size() + " messages in history.");
                break;
            }

            // Show the history so you can see what the model receives each turn
            if (input.equalsIgnoreCase("history")) {
                System.out.println("\n--- Conversation history (" +
                        conversation.getHistory().size() + " messages) ---");
                for (int i = 0; i < conversation.getHistory().size(); i++) {
                    Message msg = conversation.getHistory().get(i);
                    String preview = msg.content().length() > 80
                            ? msg.content().substring(0, 80) + "..."
                            : msg.content();
                    System.out.printf("[%d] %s: %s%n", i + 1, msg.role().toUpperCase(), preview);
                }
                System.out.println("---\n");
                continue;
            }

            // Add user message to history
            conversation.addUserMessage(input);

            // Send full history, stream the reply
            System.out.print(MODEL + ": ");
            String reply = agent.chat(conversation);

            // Add assistant reply to history — critical for memory to work
            conversation.addAssistantMessage(reply);
            System.out.println();
        }
    }
}