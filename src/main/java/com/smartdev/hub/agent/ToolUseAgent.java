package com.smartdev.hub.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Exercise 4 — Tool use: give Mistral a calculator it can call.
 *
 * What you'll learn:
 *   - How to define a tool as a JSON schema
 *   - How the model signals it wants to call a tool (tool_calls in the response)
 *   - How to detect the tool call, run the Java logic, and feed the result back
 *   - The full tool use loop: prompt → tool_call → execute → result → final answer
 *
 * NOTE: Ollama's tool use format follows the OpenAI spec.
 * With stream=false the response looks like:
 *
 *   {
 *     "message": {
 *       "role": "assistant",
 *       "content": "",
 *       "tool_calls": [{
 *         "function": {
 *           "name": "calculate",
 *           "arguments": { "expression": "12 * (5 + 3)" }
 *         }
 *       }]
 *     },
 *     "done": true
 *   }
 *
 * When tool_calls is present, content is empty — the model is saying
 * "don't reply to the user yet, run this tool first."
 */
public class ToolUseAgent {

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "mistral";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Step 1: Define the tool as a JSON schema
    // The model reads the "description" fields to know when and how to use it
    // -------------------------------------------------------------------------

    private JsonNode buildToolDefinition() throws IOException {
        String toolJson = """
            {
              "type": "function",
              "function": {
                "name": "calculate",
                "description": "Evaluates a mathematical expression and returns the numeric result. Use this whenever the user asks you to compute, calculate, or do any arithmetic.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "expression": {
                      "type": "string",
                      "description": "The mathematical expression to evaluate, e.g. '12 * (5 + 3)' or '100 / 4 + 7'"
                    }
                  },
                  "required": ["expression"]
                }
              }
            }
            """;
        return mapper.readTree(toolJson);
    }

    // -------------------------------------------------------------------------
    // Step 2: The actual Java tool implementation
    // This runs on YOUR machine — the model never sees this code
    // -------------------------------------------------------------------------

    private String executeTool(String toolName, JsonNode arguments) {
        if ("calculate".equals(toolName)) {
            String expression = arguments.path("expression").asText();
            System.out.println("  [TOOL CALLED] calculate(\"" + expression + "\")");
            try {
                double result = evaluateExpression(expression);
                String resultStr = (result == Math.floor(result))
                        ? String.valueOf((long) result)
                        : String.valueOf(result);
                System.out.println("  [TOOL RESULT] " + resultStr);
                return resultStr;
            } catch (Exception e) {
                return "Error evaluating expression: " + e.getMessage();
            }
        }
        return "Unknown tool: " + toolName;
    }

    // -------------------------------------------------------------------------
    // Step 3: The tool use loop
    // Send prompt → check for tool_call → execute → send result → get final answer
    // -------------------------------------------------------------------------

    public String chat(String systemPrompt, String userMessage) throws IOException {

        // --- Turn 1: send the user message with tool definitions ---
        ObjectNode body = buildRequestBody(systemPrompt, userMessage);
        String rawResponse = sendRequest(body);
        JsonNode response = mapper.readTree(rawResponse);
        JsonNode message = response.path("message");

        // Check if the model wants to call a tool
        JsonNode toolCalls = message.path("tool_calls");

        if (toolCalls.isMissingNode() || toolCalls.isEmpty()) {
            // No tool call — model answered directly (e.g. for non-math questions)
            System.out.println("  [No tool call — model answered directly]");
            return message.path("content").asText();
        }

        // --- Tool call detected ---
        // Extract the tool name and arguments
        JsonNode toolCall = toolCalls.get(0);
        String toolName = toolCall.path("function").path("name").asText();
        JsonNode arguments = toolCall.path("function").path("arguments");

        // Execute the tool in Java
        String toolResult = executeTool(toolName, arguments);

        // --- Turn 2: send the tool result back to the model ---
        // We add two new messages to the conversation:
        //   1. The assistant's tool_call message (what the model just said)
        //   2. A "tool" role message with the result
        ObjectNode body2 = buildRequestBodyWithToolResult(
                systemPrompt, userMessage, message, toolName, toolResult);

        String finalRawResponse = sendRequest(body2);
        JsonNode finalResponse = mapper.readTree(finalRawResponse);
        return finalResponse.path("message").path("content").asText();
    }

    // -------------------------------------------------------------------------
    // Request builders
    // -------------------------------------------------------------------------

    private ObjectNode buildRequestBody(String systemPrompt, String userMessage)
            throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");

        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage);

        // Attach the tool definition — this is what tells the model a tool exists
        ArrayNode tools = body.putArray("tools");
        tools.add(buildToolDefinition());

        return body;
    }

    private ObjectNode buildRequestBodyWithToolResult(
            String systemPrompt, String userMessage,
            JsonNode assistantMessage, String toolName, String toolResult)
            throws IOException {

        ObjectNode body = mapper.createObjectNode();
        body.put("model", MODEL);
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");

        // 1. System
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);

        // 2. Original user message
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage);

        // 3. The assistant's tool_call (replay what the model said)
        messages.add(assistantMessage);

        // 4. The tool result — role "tool" tells the model this is a tool response
        ObjectNode toolMsg = messages.addObject();
        toolMsg.put("role", "tool");
        toolMsg.put("name", toolName);
        toolMsg.put("content", toolResult);

        // Tools still attached so the model can call again if needed
        ArrayNode tools = body.putArray("tools");
        tools.add(buildToolDefinition());

        return body;
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
    // Simple expression evaluator — no external libraries needed
    // Handles: +  -  *  /  parentheses  decimals
    // -------------------------------------------------------------------------

    private double evaluateExpression(String expression) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() {
                ch = (++pos < expression.length()) ? expression.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) { nextChar(); return true; }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expression.length())
                    throw new RuntimeException("Unexpected: " + (char) ch);
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if      (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if      (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = this.pos;
                if (eat('(')) { x = parseExpression(); eat(')'); }
                else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expression.substring(startPos + 1, this.pos));
                } else {
                    throw new RuntimeException("Unexpected: " + (char) ch);
                }
                return x;
            }
        }.parse();
    }

    // -------------------------------------------------------------------------
    // Main
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        ToolUseAgent agent = new ToolUseAgent();

        String systemPrompt = """
                You are a helpful assistant. When asked to do any math or calculation,
                you MUST use the calculate tool — never compute in your head.
                """;

        System.out.println("=== Exercise 4: Tool Use — Calculator ===\n");

        // Test 1: simple arithmetic — model should call the tool
        System.out.println("--- Test 1: simple arithmetic ---");
        System.out.println("User: What is 12 * (5 + 3)?");
        String reply1 = agent.chat(systemPrompt, "What is 12 * (5 + 3)?");
        System.out.println("Mistral: " + reply1);

        System.out.println();

        // Test 2: word problem — model extracts the math, calls the tool
        System.out.println("--- Test 2: word problem ---");
        System.out.println("User: I have 3 microservices, each with 4 instances. " +
                "Each instance uses 256MB RAM. How much total RAM is that in GB?");
        String reply2 = agent.chat(systemPrompt,
                "I have 3 microservices, each with 4 instances. " +
                        "Each instance uses 256MB RAM. How much total RAM is that in GB?");
        System.out.println("Mistral: " + reply2);

        System.out.println();

        // Test 3: non-math question — model should NOT call the tool
        System.out.println("--- Test 3: no tool needed ---");
        System.out.println("User: What is the capital of France?");
        String reply3 = agent.chat(systemPrompt, "What is the capital of France?");
        System.out.println("Mistral: " + reply3);

        System.out.println("\n=== Exercise 4 complete ===");
    }
}