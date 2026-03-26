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
 * Exercise 6 — ReAct agent: Reasoning + Acting, interleaved.
 *
 * What you'll learn:
 *   - The ReAct pattern: Thought → Act → Observe → Thought → Act → ...
 *   - How making the model write its reasoning makes agents debuggable
 *   - How an agent navigates an unknown codebase (no files_to_change hints)
 *   - How to handle the "find the right file" problem with listDirectory + readFile
 *
 * The key difference from FileSystemAgent:
 *   FileSystemAgent: model decides silently, calls tools, done.
 *   ReactAgent:      model writes "Thought: ..." before every action.
 *                    You can read the console and understand WHY it did each step.
 *
 * This is how production agents like Claude Code and Devin work internally —
 * they maintain an explicit chain of reasoning alongside their tool calls.
 *
 * The agent is given issue-001.json (add pagination) with NO files_to_change hint.
 * It must explore the project structure itself to find the right files.
 */
public class ReactAgent {

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "qwen2.5-coder:7b";
    private static final String PROJECT_ROOT = System.getProperty("user.dir");
    private static final int MAX_FILE_CHARS = 6000;
    private static final int MAX_ITERATIONS = 12;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Tool definitions — same three as FileSystemAgent plus findInFile
    // -------------------------------------------------------------------------

    private JsonNode buildTools() throws IOException {
        String toolsJson = """
            [
              {
                "type": "function",
                "function": {
                  "name": "readFile",
                  "description": "Reads the content of a file. Use to inspect source code, configs, or issue files.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": { "type": "string", "description": "File path relative to project root." }
                    },
                    "required": ["path"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "listDirectory",
                  "description": "Lists files and subdirectories at a path. Use to explore the project structure when you don't know which file to read.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": { "type": "string", "description": "Directory path relative to project root. Use '.' for root." }
                    },
                    "required": ["path"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "findFile",
                  "description": "Searches the project for a file whose name contains the given keyword. Returns matching file paths. Use this when you know the class name but not the exact path.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "keyword": { "type": "string", "description": "Filename keyword to search for, e.g. 'TaskController' or 'TaskService'." }
                    },
                    "required": ["keyword"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "writeFile",
                  "description": "Writes content to a file. Use to save analysis notes or summaries.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": { "type": "string", "description": "File path relative to project root." },
                      "content": { "type": "string", "description": "Content to write." }
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
    // Tool implementations
    // -------------------------------------------------------------------------

    private String executeTool(String name, JsonNode args) {
        try {
            return switch (name) {
                case "readFile"      -> toolReadFile(args.path("path").asText());
                case "listDirectory" -> toolListDirectory(args.path("path").asText());
                case "findFile"      -> toolFindFile(args.path("keyword").asText());
                case "writeFile"     -> toolWriteFile(args.path("path").asText(),
                        args.path("content").asText());
                default -> "Unknown tool: " + name;
            };
        } catch (Exception e) {
            return "Error running " + name + ": " + e.getMessage();
        }
    }

    private String toolReadFile(String rel) throws IOException {
        Path p = Paths.get(PROJECT_ROOT, rel).normalize();
        if (!Files.exists(p)) return "File not found: " + rel;
        String content = Files.readString(p);
        if (content.length() > MAX_FILE_CHARS)
            content = content.substring(0, MAX_FILE_CHARS) + "\n...[truncated]";
        System.out.println("    [readFile] " + rel);
        return content;
    }

    private String toolListDirectory(String rel) throws IOException {
        Path dir = Paths.get(PROJECT_ROOT, rel).normalize();
        if (!Files.isDirectory(dir)) return "Not a directory: " + rel;
        try (Stream<Path> s = Files.list(dir)) {
            System.out.println("    [listDirectory] " + rel);
            return s.map(p -> Files.isDirectory(p)
                            ? p.getFileName() + "/"
                            : p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining("\n"));
        }
    }

    private String toolFindFile(String keyword) throws IOException {
        System.out.println("    [findFile] keyword=" + keyword);
        try (Stream<Path> s = Files.walk(Paths.get(PROJECT_ROOT))) {
            String results = s
                    .filter(Files::isRegularFile)
                    .map(p -> Paths.get(PROJECT_ROOT).relativize(p).toString())
                    .filter(p -> p.toLowerCase().contains(keyword.toLowerCase()))
                    // Exclude build output
                    .filter(p -> !p.contains("target"))
                    .sorted()
                    .collect(Collectors.joining("\n"));
            return results.isEmpty() ? "No files found matching: " + keyword : results;
        }
    }

    private String toolWriteFile(String rel, String content) throws IOException {
        Path p = Paths.get(PROJECT_ROOT, rel).normalize();
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        System.out.println("    [writeFile] " + rel + " (" + content.length() + " chars)");
        return "Written: " + rel;
    }

    // -------------------------------------------------------------------------
    // ReAct loop
    // The system prompt instructs the model to write "Thought:" before each action.
    // We print those thoughts so the reasoning is visible in the console.
    // -------------------------------------------------------------------------

    public String run(String goal) throws IOException {
        System.out.println("Goal: " + goal);
        System.out.println();

        String systemPrompt = """
                You are a senior Java developer agent working on SmartDev Hub,
                a Spring Boot 3 + SQLite project.

                You work in a Thought/Act/Observe loop:
                  - Before every tool call, write one sentence starting with "Thought:" explaining why you are calling that tool.
                  - After receiving a tool result, write "Thought:" again to reason about what you learned and what to do next.
                  - When you have fully answered the goal, write "Final Answer:" followed by your conclusion.

                Rules:
                  - NEVER guess file contents. Always readFile or findFile first.
                  - The issues/ directory contains task descriptions. Read them to understand the goal.
                  - The files_to_change field in issue files is for human reference only — ignore it and find the files yourself.
                  - Use findFile to locate files by class name when you don't know the path.
                  - Use listDirectory to explore unknown parts of the project.
                """;

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", goal);

        JsonNode tools = buildTools();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            body.put("stream", false);
            body.set("messages", messages);
            body.set("tools", tools);

            JsonNode response = mapper.readTree(sendRequest(body));
            JsonNode message = response.path("message");
            JsonNode toolCalls = message.path("tool_calls");

            // Handle qwen's content-embedded tool call format
            if (toolCalls.isMissingNode() || toolCalls.isEmpty()) {
                String content = message.path("content").asText().trim();

                // Strip markdown fences if present — qwen sometimes wraps in ```json ... ```
                String stripped = content;
                if (stripped.contains("```")) {
                    stripped = stripped.replaceAll("```[a-zA-Z]*\\n?", "").replace("```", "").trim();
                }

                // Check if it's a tool call embedded in content (qwen format)
                if (stripped.contains("\"name\"") && stripped.contains("\"arguments\"")) {
                    // Extract Thought lines (non-JSON lines before the tool call)
                    for (String line : content.split("\n")) {
                        String t = line.trim();
                        if (!t.isBlank() && !t.startsWith("{") && !t.startsWith("```") && !t.startsWith("\""))
                            System.out.println("  " + t);
                    }

                    ArrayNode synthetic = mapper.createArrayNode();
                    for (String line : stripped.split("\n")) {
                        line = line.trim();
                        if (line.isBlank() || !line.startsWith("{")) continue;
                        try {
                            JsonNode parsed = mapper.readTree(line);
                            if (!parsed.has("name")) continue;
                            ObjectNode norm = mapper.createObjectNode();
                            ObjectNode fn = norm.putObject("function");
                            fn.put("name", parsed.path("name").asText());
                            fn.set("arguments", parsed.path("arguments"));
                            synthetic.add(norm);
                        } catch (Exception ignored) {}
                    }

                    if (!synthetic.isEmpty()) {
                        toolCalls = synthetic;
                        ObjectNode fixedMsg = mapper.createObjectNode();
                        fixedMsg.put("role", "assistant");
                        fixedMsg.put("content", "");
                        fixedMsg.set("tool_calls", toolCalls);
                        message = fixedMsg;
                    } else {
                        // Couldn't parse tool calls — print and return
                        if (!content.isBlank()) System.out.println("  " + content);
                        return content;
                    }
                } else {
                    // No tool call — model is done, print any final text
                    if (!content.isBlank()) {
                        for (String line : content.split("\n")) {
                            if (!line.isBlank()) System.out.println("  " + line);
                        }
                    }
                    return content;
                }
            } else {
                // Structured tool_calls — print any thought in content first
                String thought = message.path("content").asText().trim();
                if (!thought.isBlank()) System.out.println("  " + thought);
            }

            // Add assistant message to history
            messages.add(message);

            // Execute each tool call
            for (JsonNode tc : toolCalls) {
                String toolName = tc.path("function").path("name").asText();
                JsonNode args   = tc.path("function").path("arguments");

                System.out.println();
                System.out.println("  Act: " + toolName + "(" + args + ")");
                String result = executeTool(toolName, args);
                System.out.println("  Observe: " + result.substring(0, Math.min(120, result.length()))
                        + (result.length() > 120 ? "..." : ""));
                System.out.println();

                ObjectNode toolMsg = messages.addObject();
                toolMsg.put("role", "tool");
                toolMsg.put("name", toolName);
                toolMsg.put("content", result);
            }
        }

        return "Max iterations reached.";
    }

    private String sendRequest(ObjectNode body) throws IOException {
        Request req = new Request.Builder()
                .url(OLLAMA_URL)
                .post(RequestBody.create(
                        mapper.writeValueAsString(body),
                        MediaType.get("application/json")))
                .build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
            return r.body().string();
        }
    }

    // -------------------------------------------------------------------------
    // Main — two tasks, no files_to_change hints
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        ReactAgent agent = new ReactAgent();

        System.out.println("=== Exercise 6: ReAct Agent ===\n");

        // Task 1: explore the project structure and summarise the architecture
        // The agent has never seen the project — it must navigate it from scratch
        System.out.println("════════════════════════════════════════");
        System.out.println("Task 1: Explore the project structure");
        System.out.println("════════════════════════════════════════");
        String result1 = agent.run(
                "Explore the SmartDev Hub project structure and produce a short " +
                        "architectural summary: what layers exist, what each does, " +
                        "and what REST endpoints are exposed. " +
                        "Start from the project root and navigate from there.");

        System.out.println("\n--- Final answer ---");
        System.out.println(result1);

        System.out.println("\n\n════════════════════════════════════════");
        System.out.println("Task 2: Read an issue and identify affected files");
        System.out.println("════════════════════════════════════════");

        // Task 2: read issue-001 (pagination) and find the files to change
        // Note: the agent is told to ignore files_to_change and find them itself
        String result2 = agent.run(
                "Read issues/issue-001.json. " +
                        "Understand what change is needed. " +
                        "Then find the source files that need to be changed to implement it — " +
                        "do NOT use the files_to_change field in the issue, figure it out yourself " +
                        "by exploring the project. " +
                        "List the files with a one-line explanation of what needs to change in each.");

        System.out.println("\n--- Final answer ---");
        System.out.println(result2);

        System.out.println("\n=== Exercise 6 complete ===");
    }
}