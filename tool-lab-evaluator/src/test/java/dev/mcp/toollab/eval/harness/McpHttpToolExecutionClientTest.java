package dev.mcp.toollab.eval.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.eval.schema.ToolSchemaValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpToolExecutionClientTest {
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private HttpServer mcpServer;
    private URI mcpEndpoint;
    private McpHttpToolExecutionClient client;
    private JsonNode lastToolMeta;
    private JsonNode lastResourceMeta;
    private int resourceReadCount;

    @BeforeEach
    void startMcpServer() throws IOException {
        lastToolMeta = null;
        lastResourceMeta = null;
        resourceReadCount = 0;
        mcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpServer.createContext("/mcp", this::handleMcpRequest);
        mcpServer.start();
        mcpEndpoint = URI.create("http://127.0.0.1:" + mcpServer.getAddress().getPort() + "/mcp");
        client = new McpHttpToolExecutionClient(mcpEndpoint, new ToolSchemaValidator(registry), mapper);
    }

    @AfterEach
    void stopMcpServer() {
        if (client != null) {
            client.close();
        }
        if (mcpServer != null) {
            mcpServer.stop(0);
        }
    }

    @Test
    void executesSuccessfulSingleToolCallOverMcpHttp() {
        try (ToolExecutionClient.ToolExecutionSession session = client.startTask(
                "mcp-wire-test",
                task("compute.single.spec.001", "single_tool"))) {
            ObjectNode args = mapper.createObjectNode().put("instanceType", "p5.48xlarge");

            ToolExecutionClient.ExecutionResult execution = session.execute("get_instance_spec", args);

            assertEquals("mcp-http", session.mode());
            assertTrue(execution.validation().valid());
            assertTrue(execution.result().success());
            assertEquals("p5.48xlarge", execution.result().result().path("instanceType").asText());
            assertEquals(session.initialStateHash(), session.finalStateHash());
            assertTrue(session.finalState().isObject());
            assertEquals(2, resourceReadCount);
        }
    }

    @Test
    void mcpToolResultIncludesTextContentAlongsideStructuredContent() throws Exception {
        ObjectNode args = mapper.createObjectNode().put("instanceType", "p5.48xlarge");

        JsonNode result = rawMcpSession().callTool("get_instance_spec", args);

        JsonNode structured = result.path("structuredContent");
        assertFalse(structured.isMissingNode());
        assertFalse(structured.isNull());

        JsonNode content = result.path("content");
        assertTrue(content.isArray());
        assertFalse(content.isEmpty());
        assertEquals("text", content.get(0).path("type").asText());

        JsonNode textJson = mapper.readTree(content.get(0).path("text").asText());
        assertEquals("p5.48xlarge", textJson.path("instanceType").asText());
        assertEquals(structured.path("instanceType").asText(), textJson.path("instanceType").asText());
        assertEquals(structured.path("acceleratorMemoryGib").asInt(), textJson.path("acceleratorMemoryGib").asInt());
    }

    @Test
    void preservesDomainErrorCodeForExpectedToolFailureOverMcpHttp() {
        ObjectNode args = mapper.createObjectNode()
                .put("planId", "plan-missing")
                .put("idempotencyKey", "commit-missing");
        EvalTask task = task("compute.error.unknown-plan.001", "error_recovery");

        ToolExecutionClient.ExecutionResult mcpExecution;
        try (ToolExecutionClient.ToolExecutionSession session = client.startTask("mcp-wire-test", task)) {
            mcpExecution = session.execute("commit_plan", args);
        }

        assertTrue(mcpExecution.validation().valid());
        assertFalse(mcpExecution.result().success());
        assertEquals("UNKNOWN_PLAN", mcpExecution.result().errorCode());
        assertTrue(mcpExecution.result().message().contains("plan-missing"));
    }

    @Test
    void executesRecommendationWithOptionalBudgetOmittedOverMcpHttp() {
        ObjectNode args = mapper.createObjectNode();
        args.putArray("candidateInstanceTypes")
                .add("p5.48xlarge")
                .add("p5e.48xlarge");
        args.put("modelBillionParameters", 70);
        args.put("precision", "bf16");
        args.put("mode", "fine_tuning");
        args.put("optimizeFor", "cheapest");

        try (ToolExecutionClient.ToolExecutionSession session = client.startTask(
                "mcp-wire-test",
                task("compute.mixed-dag.fit-price-recommend.001", "mixed_dag"))) {
            ToolExecutionClient.ExecutionResult execution = session.execute("recommend_instance", args);

            assertTrue(execution.validation().valid());
            assertTrue(execution.result().success());
            assertEquals("p5.48xlarge", execution.result().result().path("recommendedInstanceType").asText());
        }
    }

    @Test
    void mcpToolErrorsIncludeTextContentWithDomainCode() {
        ObjectNode args = mapper.createObjectNode()
                .put("planId", "plan-missing")
                .put("idempotencyKey", "commit-missing");

        JsonNode result = rawMcpSession().callTool("commit_plan", args);

        assertTrue(result.path("isError").asBoolean(false));
        JsonNode content = result.path("content");
        assertTrue(content.isArray());
        assertFalse(content.isEmpty());
        assertEquals("text", content.get(0).path("type").asText());
        String text = content.get(0).path("text").asText();
        assertTrue(text.startsWith("UNKNOWN_PLAN:"));
        assertEquals("UNKNOWN_PLAN", McpHttpToolExecutionClient.decodeMcpToolError("commit_plan", text).errorCode());
    }

    @Test
    void mcpToolErrorParsingFallsBackWhenMessageDoesNotStartWithDomainCode() {
        var result = McpHttpToolExecutionClient.decodeMcpToolError("commit_plan", "Tool failed without a domain code");

        assertFalse(result.success());
        assertEquals("MCP_TOOL_ERROR", result.errorCode());
        assertEquals("Tool failed without a domain code", result.message());
    }

    @Test
    void suppliesToolLabMetaThroughProgrammaticMcpClient() {
        EvalTask task = task("compute.single.spec.001", "single_tool");
        try (ToolExecutionClient.ToolExecutionSession session = client.startTask("mcp-wire-test", task)) {
            assertEquals(session.stateId(), lastResourceMeta.path("toolLabStateId").asText());
            assertEquals(task.taskId(), lastResourceMeta.path("toolLabTaskId").asText());

            session.execute("get_instance_spec", mapper.createObjectNode().put("instanceType", "p5.48xlarge"));

            assertEquals(session.stateId(), lastToolMeta.path("toolLabStateId").asText());
            assertEquals(task.taskId(), lastToolMeta.path("toolLabTaskId").asText());
        }
    }

    private void handleMcpRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            writeHttp(exchange, 405, "");
            return;
        }
        JsonNode request = mapper.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        if ("notifications/initialized".equals(method)) {
            writeHttp(exchange, 202, "");
            return;
        }

        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (request.has("id")) {
            envelope.set("id", request.path("id"));
        }
        envelope.set("result", switch (method) {
            case "initialize" -> initializeResult();
            case "resources/read" -> stateResult(request.path("params"));
            case "tools/call" -> toolResult(request.path("params"));
            default -> throw new IllegalArgumentException("Unsupported MCP method: " + method);
        });
        exchange.getResponseHeaders().set("Mcp-Session-Id", "test-mcp-session");
        writeHttp(exchange, 200, writeJson(envelope));
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities");
        result.putObject("serverInfo").put("name", "tool-lab-mcp-test-stub");
        return result;
    }

    private ObjectNode stateResult(JsonNode params) {
        resourceReadCount++;
        lastResourceMeta = params.path("_meta").deepCopy();
        ObjectNode stateEnvelope = mapper.createObjectNode();
        stateEnvelope.putObject("state");
        stateEnvelope.put("stateHash", "test-state-hash");

        ObjectNode content = mapper.createObjectNode();
        content.put("uri", "tool-lab://state/test");
        content.put("mimeType", "application/json");
        content.put("text", writeJson(stateEnvelope));

        ObjectNode result = mapper.createObjectNode();
        result.putArray("contents").add(content);
        return result;
    }

    private ObjectNode toolResult(JsonNode params) {
        lastToolMeta = params.path("_meta").deepCopy();
        String toolName = params.path("name").asText();
        return switch (toolName) {
            case "get_instance_spec" -> successfulToolResult(instanceSpec(params.path("arguments")));
            case "recommend_instance" -> successfulToolResult(recommendation());
            case "commit_plan" -> failedToolResult("UNKNOWN_PLAN: Unknown plan: "
                    + params.path("arguments").path("planId").asText());
            default -> throw new IllegalArgumentException("Unsupported test tool: " + toolName);
        };
    }

    private ObjectNode instanceSpec(JsonNode arguments) {
        ObjectNode result = mapper.createObjectNode();
        result.put("instanceType", arguments.path("instanceType").asText());
        result.put("acceleratorMemoryGib", 640);
        return result;
    }

    private ObjectNode recommendation() {
        ObjectNode result = mapper.createObjectNode();
        result.put("recommendedInstanceType", "p5.48xlarge");
        return result;
    }

    private ObjectNode successfulToolResult(JsonNode structuredContent) {
        ObjectNode result = mapper.createObjectNode();
        result.set("structuredContent", structuredContent);
        result.putArray("content").add(textContent(writeJson(structuredContent)));
        return result;
    }

    private ObjectNode failedToolResult(String text) {
        ObjectNode result = mapper.createObjectNode();
        result.put("isError", true);
        result.putArray("content").add(textContent(text));
        return result;
    }

    private ObjectNode textContent(String text) {
        ObjectNode content = mapper.createObjectNode();
        content.put("type", "text");
        content.put("text", text);
        return content;
    }

    private void writeHttp(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (!body.isBlank()) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private EvalTask task(String taskId, String category) {
        return new EvalTask(
                taskId,
                "test-template",
                "eval",
                "compute_planning",
                category,
                "test prompt",
                "default",
                "final_answer",
                4);
    }

    private RawMcpSession rawMcpSession() {
        RawMcpSession session = new RawMcpSession();
        session.initialize();
        return session;
    }

    private final class RawMcpSession {
        private String sessionId;

        void initialize() {
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.putObject("capabilities").putObject("roots");
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "tool-lab-evaluator-test");
            clientInfo.put("version", "0.1.0-SNAPSHOT");
            sendRpc("initialize", params, true);
            sendRpc("notifications/initialized", mapper.createObjectNode(), false);
        }

        JsonNode callTool(String toolName, ObjectNode arguments) {
            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", arguments);
            ObjectNode meta = params.putObject("_meta");
            meta.put("toolLabStateId", "mcp-compat-test");
            meta.put("toolLabTaskId", "mcp-compat-test");
            return sendRpc("tools/call", params, true);
        }

        private JsonNode sendRpc(String method, JsonNode params, boolean expectResult) {
            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            if (expectResult) {
                request.put("id", "raw-mcp-test-" + method);
            }
            request.put("method", method);
            request.set("params", params);

            HttpRequest.Builder builder = HttpRequest.newBuilder(mcpEndpoint)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(writeJson(request)));
            if (sessionId != null && !sessionId.isBlank()) {
                builder.header("Mcp-Session-Id", sessionId);
            }

            HttpResponse<String> response;
            try {
                response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new IllegalStateException("MCP HTTP request failed: " + method, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("MCP HTTP request interrupted: " + method, e);
            }

            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MCP HTTP " + method + " failed with status "
                        + response.statusCode() + ": " + response.body());
            }
            if (!expectResult || response.body() == null || response.body().isBlank()) {
                return mapper.createObjectNode();
            }
            JsonNode envelope = parseHttpBody(response.body());
            if (envelope.hasNonNull("error")) {
                throw new IllegalStateException("MCP " + method + " returned error: "
                        + envelope.path("error").toString());
            }
            return envelope.path("result");
        }
    }

    private JsonNode parseHttpBody(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("event:") || trimmed.startsWith("data:")) {
            StringBuilder data = new StringBuilder();
            for (String line : trimmed.split("\\R")) {
                if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
            }
            trimmed = data.toString();
        }
        try {
            return mapper.readTree(trimmed);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("MCP HTTP response was not JSON: " + body, e);
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write MCP JSON-RPC request", e);
        }
    }
}
