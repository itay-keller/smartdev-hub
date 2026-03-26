package com.smartdev.hub.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * Shared utility for parsing Ollama tool call responses.
 *
 * The problem: different models (and different Ollama versions) return tool calls
 * in different formats:
 *
 *   Format A — structured (Mistral, llama3.1):
 *     message.tool_calls = [{ function: { name, arguments } }]
 *     message.content = ""
 *
 *   Format B — plain JSON in content (qwen2.5-coder):
 *     message.tool_calls = missing
 *     message.content = '{"name":"readFile","arguments":{"path":"..."}}'
 *
 *   Format C — fenced JSON in content (qwen2.5-coder variant):
 *     message.tool_calls = missing
 *     message.content = '```json\n{"name":"readFile","arguments":{...}}\n```'
 *
 *   Format D — multiple tool calls, newline-separated (qwen2.5-coder):
 *     message.content = '{"name":"readFile",...}\n{"name":"readFile",...}'
 *
 * This class normalises all formats into a single ParsedResponse object
 * so agent code never needs to deal with format differences.
 */
public class OllamaToolParser {

    private static final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Result type returned to the agent loop
    // -------------------------------------------------------------------------

    public record ParsedResponse(
            boolean hasToolCalls,
            JsonNode toolCalls,      // ArrayNode of { function: { name, arguments } }
            JsonNode normalizedMessage, // cleaned-up message node to add to history
            String thoughtText       // any Thought: / reasoning text the model wrote
    ) {}

    // -------------------------------------------------------------------------
    // Main parse method — call this on every Ollama response
    // -------------------------------------------------------------------------

    public static ParsedResponse parse(JsonNode response) throws IOException {
        JsonNode message  = response.path("message");
        JsonNode toolCalls = message.path("tool_calls");

        // --- Format A: structured tool_calls array (Mistral, llama3.1) ---
        if (!toolCalls.isMissingNode() && !toolCalls.isEmpty()) {
            return new ParsedResponse(true, toolCalls, message, extractThought(message));
        }

        // --- Formats B / C / D: tool call embedded in content string ---
        String content = message.path("content").asText("").trim();
        String stripped = stripFences(content);

        if (looksLikeToolCall(stripped)) {
            String thought = extractThoughtLines(content);
            ArrayNode synthetic = parsEmbeddedToolCalls(stripped);

            if (!synthetic.isEmpty()) {
                // Build a normalised message node so history stays consistent
                ObjectNode normMsg = mapper.createObjectNode();
                normMsg.put("role", "assistant");
                normMsg.put("content", "");
                normMsg.set("tool_calls", synthetic);
                return new ParsedResponse(true, synthetic, normMsg, thought);
            }
        }

        // --- No tool call: model finished, content is the final answer ---
        return new ParsedResponse(false, mapper.createArrayNode(), message, content);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Strip ```json ... ``` or ``` ... ``` markdown fences */
    private static String stripFences(String text) {
        if (!text.contains("```")) return text;
        return text.replaceAll("```[a-zA-Z]*\\n?", "")
                .replace("```", "")
                .trim();
    }

    /** True if the stripped text contains JSON with "name" and "arguments" keys */
    private static boolean looksLikeToolCall(String text) {
        return text.contains("\"name\"") && text.contains("\"arguments\"");
    }

    /**
     * Parse one or more newline-separated JSON tool call objects.
     * Each line: {"name":"toolName","arguments":{...}}
     * Returns normalised array: [{ function: { name, arguments } }, ...]
     */
    private static ArrayNode parsEmbeddedToolCalls(String text) {
        ArrayNode result = mapper.createArrayNode();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isBlank() || !line.startsWith("{")) continue;
            try {
                JsonNode parsed = mapper.readTree(line);
                if (!parsed.has("name")) continue;
                ObjectNode norm = mapper.createObjectNode();
                ObjectNode fn   = norm.putObject("function");
                fn.put("name", parsed.path("name").asText());
                fn.set("arguments", parsed.path("arguments"));
                result.add(norm);
            } catch (Exception ignored) {
                // Skip malformed lines
            }
        }
        return result;
    }

    /**
     * Extract reasoning text (Thought: lines) that precedes the tool call JSON.
     * These are lines that don't start with { or ``` — they're the model thinking aloud.
     */
    private static String extractThoughtLines(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (!t.isBlank() && !t.startsWith("{") && !t.startsWith("```") && !t.startsWith("\"")) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /** Extract thought from structured message content (Format A) */
    private static String extractThought(JsonNode message) {
        return message.path("content").asText("").trim();
    }
}