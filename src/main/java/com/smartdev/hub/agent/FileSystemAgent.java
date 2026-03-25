package com.smartdev.hub.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Exercise 5 — File system tools: the agent reads your actual project.
 *
 * What you'll learn:
 *   - How to define and register multiple tools at once
 *   - How to dispatch tool calls to the right Java method
 *   - How the agent autonomously decides WHICH tool to call and in what order
 *   - The pattern that all real coding agents (Copilot, Cursor, Claude Code) use
 *
 * Tools exposed to the model:
 *   readFile(path)          — reads a file and returns its content
 *   listDirectory(path)     — lists files and subdirectories at a path
 *   writeFile(path, content)— writes content to a file (creates if not exists)
 *
 * Try asking:
 *   "Read the TaskService and tell me what methods it has"
 *   "List the files in src/main/java/com/smartdev/hub/service"
 *   "Read issue-002.json and explain what bug needs to be fixed"
 *   "Read TaskService.java and TaskServiceTest.java and tell me if the tests cover all methods"
 */
public class FileSystemAgent {

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "qwen2.5-coder:7b";

    // Root of the SmartDev Hub project — adjust if your path differs
    private static final String PROJECT_ROOT = System.getProperty("user.dir");

    // Max chars returned per file — prevents context overflow on large files
    private static final int MAX_FILE_CHARS = 6000;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Tool definitions — all three tools declared as JSON schemas
    // -------------------------------------------------------------------------

    private JsonNode buildToolDefinitions() throws IOException {
        String toolsJson = """
            [
              {
                "type": "function",
                "function": {
                  "name": "readFile",
                  "description": "Reads the full content of a file. Use this to inspect source code, configuration files, issue files, or any text file in the project.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": {
                        "type": "string",
                        "description": "Path to the file, relative to the project root. Example: 'src/main/java/com/smartdev/hub/service/TaskService.java'"
                      }
                    },
                    "required": ["path"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "listDirectory",
                  "description": "Lists all files and subdirectories at a given path. Use this to explore the project structure before deciding which files to read.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": {
                        "type": "string",
                        "description": "Directory path relative to project root. Use '.' for the project root."
                      }
                    },
                    "required": ["path"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "writeFile",
                  "description": "Writes content to a file. Creates the file if it does not exist, overwrites if it does. Use this to save notes, summaries, or code changes.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": {
                        "type": "string",
                        "description": "Path to the file to write, relative to project root."
                      },
                      "content": {
                        "type": "string",
                        "description": "The full content to write to the file."
                      }
                    },
                    "required": ["path", "content"]
                  }
                }
              }
            ]
            """;
        return mapper.readTree(toolsJson);
    }

    // -------------------------------------------------------------------------
    // Tool dispatcher — routes tool_call to the right Java method
    // -------------------------------------------------------------------------

    private String executeTool(String toolName, JsonNode args) {
        try {
            return switch (toolName) {
                case "readFile"      -> toolReadFile(args.path("path").asText());
                case "listDirectory" -> toolListDirectory(args.path("path").asText());
                case "writeFile"     -> toolWriteFile(
                        args.path("path").asText(),
                        args.path("content").asText());
                default -> "Error: unknown tool '" + toolName + "'";
            };
        } catch (Exception e) {
            return "Error executing " + toolName + ": " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Tool implementations
    // -------------------------------------------------------------------------

    private String toolReadFile(String relativePath) throws IOException {
        Path path = Paths.get(PROJECT_ROOT, relativePath).normalize();

        // Safety check — don't allow reading outside the project
        if (!path.startsWith(Paths.get(PROJECT_ROOT))) {
            return "Error: access denied — path is outside project root";
        }

        if (!Files.exists(path)) {
            return "Error: file not found at " + relativePath;
        }

        String content = Files.readString(path);

        // Truncate if too large to avoid overflowing the model's context
        if (content.length() > MAX_FILE_CHARS) {
            content = content.substring(0, MAX_FILE_CHARS) +
                    "\n... [truncated at " + MAX_FILE_CHARS + " chars]";
        }

        System.out.println("  [readFile] " + relativePath +
                " (" + content.length() + " chars)");
        return content;
    }

    private String toolListDirectory(String relativePath) throws IOException {
        Path dir = Paths.get(PROJECT_ROOT, relativePath).normalize();

        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return "Error: not a directory: " + relativePath;
        }

        try (Stream<Path> entries = Files.list(dir)) {
            String listing = entries
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return Files.isDirectory(p) ? name + "/" : name;
                    })
                    .sorted()
                    .collect(Collectors.joining("\n"));

            System.out.println("  [listDirectory] " + relativePath);
            return listing.isEmpty() ? "(empty directory)" : listing;
        }
    }

    private String toolWriteFile(String relativePath, String content) throws IOException {
        Path path = Paths.get(PROJECT_ROOT, relativePath).normalize();

        if (!path.startsWith(Paths.get(PROJECT_ROOT))) {
            return "Error: access denied — path is outside project root";
        }

        Files.createDirectories(path.getParent());
        Files.writeString(path, content);

        System.out.println("  [writeFile] " + relativePath +
                " (" + content.length() + " chars written)");
        return "OK: wrote " + content.length() + " chars to " + relativePath;
    }

    // -------------------------------------------------------------------------
    // Agent loop — keeps calling tools until the model gives a final answer
    // This is the core of Phase 3's ReAct loop, in simplified form
    // -------------------------------------------------------------------------

    public String run(String systemPrompt, String userMessage) throws IOException {

        System.out.println("User: " + userMessage);
        System.out.println();

        // Build initial message list
        ArrayNode messages = mapper.createArrayNode();

        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage);

        JsonNode tools = buildToolDefinitions();

        // Loop: send messages → if tool_call, execute and add result → repeat
        int maxIterations = 6; // safety limit
        for (int i = 0; i < maxIterations; i++) {

            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            body.put("stream", false);
            body.set("messages", messages);
            body.set("tools", tools);

            String raw = sendRequest(body);
            JsonNode response = mapper.readTree(raw);
            JsonNode message = response.path("message");
            JsonNode toolCalls = message.path("tool_calls");

            // qwen2.5-coder puts tool calls as JSON text inside content instead of tool_calls
            // Detect this and parse it into a proper toolCalls node
            if (toolCalls.isMissingNode() || toolCalls.isEmpty()) {
                String content = message.path("content").asText().trim();
                if (content.startsWith("{\"name\"")) {
                    // One or more tool calls embedded as newline-separated JSON objects in content
                    // e.g. {"name":"readFile","arguments":{...}}\n{"name":"readFile","arguments":{...}}
                    ArrayNode syntheticToolCalls = mapper.createArrayNode();
                    for (String line : content.split("\n")) {
                        line = line.trim();
                        if (line.isBlank()) continue;
                        JsonNode parsed = mapper.readTree(line);
                        // Normalise to the standard tool_calls format:
                        // { "function": { "name": "...", "arguments": {...} } }
                        ObjectNode normalized = mapper.createObjectNode();
                        ObjectNode fn = normalized.putObject("function");
                        fn.put("name", parsed.path("name").asText());
                        fn.set("arguments", parsed.path("arguments"));
                        syntheticToolCalls.add(normalized);
                    }
                    toolCalls = syntheticToolCalls;

                    // Replace the message node with a clean version so history is consistent
                    ObjectNode fixedMessage = mapper.createObjectNode();
                    fixedMessage.put("role", "assistant");
                    fixedMessage.put("content", "");
                    fixedMessage.set("tool_calls", toolCalls);
                    message = fixedMessage;
                }
            }

            // No tool call → model is done, return the final answer
            if (toolCalls.isMissingNode() || toolCalls.isEmpty()) {
                String finalAnswer = message.path("content").asText();
                return finalAnswer;
            }

            // Add the assistant's tool_call message to history
            messages.add(message);

            // Execute each tool call and add results to history
            for (JsonNode toolCall : toolCalls) {
                String toolName = toolCall.path("function").path("name").asText();
                JsonNode args   = toolCall.path("function").path("arguments");

                String result = executeTool(toolName, args);

                ObjectNode toolResult = messages.addObject();
                toolResult.put("role", "tool");
                toolResult.put("name", toolName);
                toolResult.put("content", result);
            }

            // Loop again — model will now process the tool results
        }

        return "Error: max iterations reached without a final answer.";
    }

    private String sendRequest(ObjectNode body) throws IOException {
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
            return response.body().string();
        }
    }

    // -------------------------------------------------------------------------
    // Main — three progressively interesting tasks
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        FileSystemAgent agent = new FileSystemAgent();

        String systemPrompt = """
                You are a senior Java developer assistant working on SmartDev Hub,
                a Spring Boot 3 + SQLite developer productivity platform.
                You have tools to read files, list directories, and write files.

                CRITICAL RULES — you must follow these without exception:
                1. You MUST call readFile before answering ANY question about file contents.
                2. You MUST NOT invent, guess, or recall file contents from memory.
                3. If asked about a file, your first action is ALWAYS a readFile tool call.
                4. Never describe what a file "might contain" — read it first, then describe what it DOES contain.
                5. If a readFile call returns an error, report the error — do not invent the content.
                """;

        System.out.println("=== Exercise 5: File System Tools ===");
        System.out.println("PROJECT_ROOT = " + PROJECT_ROOT);
        System.out.println();

        // --- Task 1: explore and summarise a service class ---
        System.out.println("======== Task 1 ========");
        String answer1 = agent.run(systemPrompt,
                "Read TaskService.java and list all its public methods with a one-line description of each.");
        System.out.println(MODEL + ": " + answer1);

        System.out.println();

        // --- Task 2: read a bug report and explain it ---
        System.out.println("======== Task 2 ========");
        String answer2 = agent.run(systemPrompt,
                "Read issues/issue-002.json and explain in plain English what the bug is, " +
                        "which file needs to change, and what the fix should be.");
        System.out.println(MODEL + ": " + answer2);

        System.out.println();

        // --- Task 3: cross-file analysis ---
        System.out.println("======== Task 3 ========");
        String answer3 = agent.run(systemPrompt,
                "Read TaskService.java and TaskServiceTest.java. " +
                        "Which public methods in TaskService are NOT covered by a test? " +
                        "Write a short summary to agent-notes/coverage-gaps.md");
        System.out.println(MODEL + ": " + answer3);

        System.out.println("\n=== Exercise 5 complete ===");
    }
}