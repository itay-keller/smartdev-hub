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
 * Exercise 7 — Coding agent: reads a bug report, fixes the code, runs tests,
 * self-corrects if tests fail, commits when green.
 *
 * What you'll learn:
 *   - How to give an agent a writeFile tool that overwrites source code
 *   - How to run Maven from Java (Runtime.exec) and capture the output
 *   - How to feed test failure output back to the model so it can self-correct
 *   - How to run Git commands from Java to commit the fix
 *   - The full dev loop: read issue → find file → fix code → test → commit
 *
 * Target: Issue #2 — NPE in TaskService.getTasksSortedByPriority()
 * The agent must:
 *   1. Read issues/issue-002.json
 *   2. Find and read TaskService.java
 *   3. Write the fix (null-safe comparator)
 *   4. Find and read TaskServiceTest.java
 *   5. Update the test (change "expects NPE" to "expects null-last ordering")
 *   6. Run mvn test — if red, read the failure and fix again
 *   7. Commit the fix with a meaningful message
 */
public class CodingAgent {

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL      = "qwen2.5-coder:7b";
    private static final String PROJECT_ROOT = System.getProperty("user.dir");
    private static final int    MAX_FILE_CHARS  = 8000;
    private static final int    MAX_ITERATIONS  = 16;
    private static final int    MAX_TEST_OUTPUT = 3000;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Tool definitions
    // -------------------------------------------------------------------------

    private JsonNode buildTools() throws IOException {
        String json = """
            [
              {
                "type": "function",
                "function": {
                  "name": "readFile",
                  "description": "Read a file's content. Always read the current file before modifying it.",
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
                  "name": "findFile",
                  "description": "Find files whose name contains the given keyword. Use to locate source files without knowing the full path.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "keyword": { "type": "string", "description": "Filename keyword, e.g. 'TaskService'." }
                    },
                    "required": ["keyword"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "writeFile",
                  "description": "Overwrite a file with new content. Use to apply code fixes. Always write the COMPLETE file content, not just the changed lines.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "path": { "type": "string", "description": "File path relative to project root." },
                      "content": { "type": "string", "description": "The COMPLETE new file content." }
                    },
                    "required": ["path", "content"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "runTests",
                  "description": "Runs 'mvn test' and returns the output. Use after every code change to verify the fix is correct. Returns PASS or FAIL with details.",
                  "parameters": {
                    "type": "object",
                    "properties": {}
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "gitCommit",
                  "description": "Commits all changed files with the given message. Only call this after runTests returns PASS.",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "message": { "type": "string", "description": "Git commit message, e.g. 'fix: null-safe comparator in TaskService'." }
                    },
                    "required": ["message"]
                  }
                }
              }
            ]
            """;
        return mapper.readTree(json);
    }

    // -------------------------------------------------------------------------
    // Tool implementations
    // -------------------------------------------------------------------------

    private String executeTool(String name, JsonNode args) {
        try {
            return switch (name) {
                case "readFile"  -> toolReadFile(args.path("path").asText());
                case "findFile"  -> toolFindFile(args.path("keyword").asText());
                case "writeFile" -> toolWriteFile(args.path("path").asText(),
                        args.path("content").asText());
                case "runTests"  -> toolRunTests();
                case "gitCommit" -> toolGitCommit(args.path("message").asText());
                default -> "Unknown tool: " + name;
            };
        } catch (Exception e) {
            return "Error in " + name + ": " + e.getMessage();
        }
    }

    private String toolReadFile(String rel) throws IOException {
        Path p = Paths.get(PROJECT_ROOT, rel).normalize();
        if (!Files.exists(p)) return "File not found: " + rel;
        String content = Files.readString(p);
        if (content.length() > MAX_FILE_CHARS)
            content = content.substring(0, MAX_FILE_CHARS) + "\n...[truncated]";
        System.out.println("    [readFile] " + rel + " (" + content.length() + " chars)");
        return content;
    }

    private String toolFindFile(String keyword) throws IOException {
        System.out.println("    [findFile] " + keyword);
        try (Stream<Path> s = Files.walk(Paths.get(PROJECT_ROOT))) {
            String results = s
                    .filter(Files::isRegularFile)
                    .map(p -> Paths.get(PROJECT_ROOT).relativize(p).toString().replace('\\', '/'))
                    .filter(p -> p.toLowerCase().contains(keyword.toLowerCase()))
                    .filter(p -> !p.contains("target/"))
                    .sorted()
                    .collect(Collectors.joining("\n"));
            return results.isEmpty() ? "No files found for: " + keyword : results;
        }
    }

    private static final java.util.Set<String> PROTECTED_FILES = java.util.Set.of(
            "pom.xml", ".gitignore", "application.properties"
    );

    private String toolWriteFile(String rel, String content) throws IOException {
        // Safety: never let the agent overwrite build/config files
        String filename = Paths.get(rel).getFileName().toString();
        if (PROTECTED_FILES.contains(filename)) {
            System.out.println("    [writeFile] BLOCKED — " + rel + " is protected");
            return "Error: " + filename + " is a protected file and cannot be modified by the agent.";
        }

        Path p = Paths.get(PROJECT_ROOT, rel).normalize();
        if (!p.startsWith(Paths.get(PROJECT_ROOT)))
            return "Error: path outside project root";
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        System.out.println("    [writeFile] " + rel + " (" + content.length() + " chars)");
        return "OK: wrote " + rel;
    }

    private String toolRunTests() throws IOException, InterruptedException {
        System.out.println("    [runTests] running mvn test...");

        String mvnCmd = findMaven();
        if (mvnCmd == null) {
            return "Tests: FAIL\n\nMaven not found. " +
                    "Please add Maven's bin directory to your system PATH, " +
                    "or set MAVEN_HOME environment variable.";
        }
        System.out.println("    [runTests] using: " + mvnCmd);

        ProcessBuilder pb = new ProcessBuilder(mvnCmd, "test", "--batch-mode")
                .directory(Paths.get(PROJECT_ROOT).toFile())
                .redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (output.length() > MAX_TEST_OUTPUT) {
            output = "...[truncated]\n" +
                    output.substring(output.length() - MAX_TEST_OUTPUT);
        }

        String status = exitCode == 0 ? "PASS" : "FAIL";
        System.out.println("    [runTests] " + status);
        return "Tests: " + status + "\n\n" + output;
    }

    /**
     * Finds the mvn executable by checking:
     * 1. MAVEN_HOME environment variable
     * 2. mvn / mvn.cmd on system PATH (via where/which)
     * 3. IntelliJ's bundled Maven in common locations
     */
    private String findMaven() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // 1. Check MAVEN_HOME
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome == null) mavenHome = System.getenv("M2_HOME");
        if (mavenHome != null) {
            String cmd = mavenHome + "/bin/mvn" + (isWindows ? ".cmd" : "");
            if (Paths.get(cmd).toFile().exists()) return cmd;
        }

        // 2. Try mvn.cmd / mvn directly (relies on PATH)
        String[] candidates = isWindows
                ? new String[]{"mvn.cmd", "mvn"}
                : new String[]{"mvn"};

        for (String candidate : candidates) {
            try {
                Process p = new ProcessBuilder(isWindows ? "where" : "which", candidate)
                        .redirectErrorStream(true).start();
                String found = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                if (!found.isBlank() && !found.contains("Could not find")) {
                    return found.split("\n")[0].trim(); // take first result
                }
            } catch (Exception ignored) {}
        }

        // 3. Check IntelliJ bundled Maven (common Windows paths)
        if (isWindows) {
            String[] intellijPaths = {
                    System.getProperty("user.home") + "/AppData/Local/JetBrains/Toolbox",
                    "C:/Program Files/JetBrains"
            };
            for (String base : intellijPaths) {
                try (Stream<Path> s = Files.walk(Paths.get(base), 8)) {
                    java.util.Optional<Path> mvn = s
                            .filter(p -> p.getFileName().toString().equals("mvn.cmd"))
                            .filter(p -> p.toString().contains("maven"))
                            .findFirst();
                    if (mvn.isPresent()) return mvn.get().toString();
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    private String toolGitCommit(String message) throws IOException, InterruptedException {
        System.out.println("    [gitCommit] " + message);

        // Stage all changed files
        runCommand("git", "add", "-A");

        // Commit
        String[] result = runCommand("git", "commit", "-m", message);
        if (result[1].contains("nothing to commit")) {
            return "Nothing to commit — no files changed.";
        }
        return "Committed: " + message + "\n" + result[0];
    }

    private String[] runCommand(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(Paths.get(PROJECT_ROOT).toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return new String[]{output, output};
    }

    // -------------------------------------------------------------------------
    // Agent loop — same ReAct pattern, using OllamaToolParser
    // -------------------------------------------------------------------------

    public String run(String goal) throws IOException {
        System.out.println("Goal: " + goal);
        System.out.println();

        String systemPrompt = """
                You are an expert Java developer agent working on SmartDev Hub,
                a Spring Boot 3 + SQLite project.

                Your job is to fix bugs by following this workflow:
                  1. Read the issue file to understand the bug.
                  2. Use findFile to locate the relevant source files.
                  3. Read each file carefully before modifying it.
                  4. Write the COMPLETE fixed file using writeFile — never partial content.
                  5. Run runTests to verify the fix.
                  6. If tests FAIL, read the failure output carefully and fix again.
                  7. When tests PASS, call gitCommit with a clear conventional commit message.

                Rules:
                  - ALWAYS read a file before writing it.
                  - ALWAYS write the COMPLETE file content — never snippets or diffs.
                  - ALWAYS run tests after every code change.
                  - NEVER commit if tests are failing.
                  - The files_to_change field in issue files is for reference only — find files yourself.
                  - Use Comparator.nullsLast() for null-safe sorting in Java.
                  - Write one sentence starting with "Thought:" before each tool call.
                """;

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode sys  = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", goal);

        JsonNode tools = buildTools();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            System.out.println("\n--- Iteration " + (i + 1) + " ---");

            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            body.put("stream", false);
            body.set("messages", messages);
            body.set("tools", tools);

            JsonNode response = mapper.readTree(sendRequest(body));
            OllamaToolParser.ParsedResponse parsed = OllamaToolParser.parse(response);

            // Print Thought
            if (!parsed.thoughtText().isBlank()) {
                for (String line : parsed.thoughtText().split("\n")) {
                    if (!line.isBlank()) System.out.println("  " + line);
                }
            }

            // No tool call — agent finished
            if (!parsed.hasToolCalls()) {
                System.out.println("\n=== Agent finished ===");
                return parsed.thoughtText();
            }

            // Add assistant message to history
            messages.add(parsed.normalizedMessage());

            // Execute tools
            for (JsonNode tc : parsed.toolCalls()) {
                String toolName = tc.path("function").path("name").asText();
                JsonNode args   = tc.path("function").path("arguments");

                System.out.println("  Act: " + toolName);
                String result = executeTool(toolName, args);

                // Print a short preview of the result
                String preview = result.length() > 200
                        ? result.substring(0, 200) + "..." : result;
                System.out.println("  Observe: " + preview);

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
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        CodingAgent agent = new CodingAgent();

        System.out.println("=== Exercise 7: Coding Agent ===");
        System.out.println("Target: Issue #2 — NPE in TaskService.getTasksSortedByPriority()");
        System.out.println("The agent will read the issue, fix the code, run tests, and commit.");
        System.out.println();

        String result = agent.run(
                "Read issues/issue-002.json. " +
                        "Find and fix the bug described in the issue. " +
                        "Also update the test in TaskServiceTest that currently expects a NullPointerException " +
                        "— it should instead assert that tasks with null priority appear at the END of the sorted list. " +
                        "Run the tests to verify everything passes. " +
                        "Then commit with a conventional commit message.");

        System.out.println("\n--- Final answer ---");
        System.out.println(result);
        System.out.println("\n=== Exercise 7 complete ===");
    }
}