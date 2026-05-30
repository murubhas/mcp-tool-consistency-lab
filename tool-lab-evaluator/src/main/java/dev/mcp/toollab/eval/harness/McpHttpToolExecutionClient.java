package dev.mcp.toollab.eval.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpMetaSupplier;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResourceContents;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.mcp.toollab.contract.ToolCallResult;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ToolSchemaValidator;
import dev.mcp.toollab.eval.schema.ValidationResult;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class McpHttpToolExecutionClient implements ToolExecutionClient {
    public static final String MODE = "mcp-http";
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String META_STATE_ID = "toolLabStateId";
    private static final String META_TASK_ID = "toolLabTaskId";
    private static final Pattern DOMAIN_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final URI endpoint;
    private final ToolSchemaValidator validator;
    private final ObjectMapper mapper;
    private final McpClient client;

    public McpHttpToolExecutionClient(URI endpoint, ToolSchemaValidator validator, ObjectMapper mapper) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.client = newMcpClient();
    }

    static McpMetaSupplier toolLabMetaSupplier() {
        return context -> {
            if (context == null || context.invocationContext() == null) {
                return Map.of();
            }
            InvocationParameters parameters = context.invocationContext().invocationParameters();
            if (parameters == null
                    || !parameters.containsKey(META_STATE_ID)
                    || !parameters.containsKey(META_TASK_ID)) {
                return Map.of();
            }
            Object stateId = parameters.get(META_STATE_ID);
            Object taskId = parameters.get(META_TASK_ID);
            return Map.of(
                    META_STATE_ID, String.valueOf(stateId),
                    META_TASK_ID, String.valueOf(taskId));
        };
    }

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public ToolExecutionSession startTask(String runId, EvalTask task) {
        String stateId = safeId(runId + "-" + task.taskId() + "-" + UUID.randomUUID());
        McpSession session = new McpSession(stateId, task.taskId());
        session.refreshInitialState();
        return session;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to close MCP client", e);
        }
    }

    private McpClient newMcpClient() {
        return DefaultMcpClient.builder()
                .key("tool-lab-evaluator")
                .clientName("tool-lab-evaluator")
                .clientVersion("0.1.0-SNAPSHOT")
                .protocolVersion(PROTOCOL_VERSION)
                .transport(StreamableHttpMcpTransport.builder()
                        .url(endpoint.toString())
                        .timeout(Duration.ofSeconds(10))
                        .build())
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .resourcesTimeout(Duration.ofSeconds(30))
                .autoHealthCheck(false)
                .metaSupplier(toolLabMetaSupplier())
                .build();
    }

    private String safeId(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private final class McpSession implements ToolExecutionSession {
        private final String stateId;
        private final String taskId;
        private final InvocationContext invocationContext;
        private JsonNode initialState;
        private String initialStateHash;
        private JsonNode finalState;
        private String finalStateHash;

        private McpSession(String stateId, String taskId) {
            this.stateId = stateId;
            this.taskId = taskId;
            this.invocationContext = InvocationContext.builder()
                    .invocationId(UUID.randomUUID())
                    .interfaceName(McpHttpToolExecutionClient.class.getName())
                    .methodName("execute")
                    .invocationParameters(InvocationParameters.from(Map.of(
                            META_STATE_ID, stateId,
                            META_TASK_ID, taskId)))
                    .timestamp(Instant.now())
                    .build();
        }

        @Override
        public String mode() {
            return MODE;
        }

        @Override
        public String stateId() {
            return stateId;
        }

        @Override
        public JsonNode initialState() {
            return initialState;
        }

        @Override
        public String initialStateHash() {
            return initialStateHash;
        }

        @Override
        public ExecutionResult execute(String toolName, JsonNode arguments) {
            ValidationResult validation = validator.validate(toolName, arguments);
            if (!validation.valid()) {
                return new ExecutionResult(validation, ToolCallResult.failure(
                        toolName,
                        "SCHEMA_VALIDATION_FAILED",
                        String.join("; ", validation.errors())));
            }

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(toolName)
                    .arguments(writeJson(arguments))
                    .build();
            try {
                ToolExecutionResult result = client.executeTool(request, invocationContext);
                return new ExecutionResult(validation, decodeToolResult(toolName, result));
            } catch (ToolExecutionException e) {
                return new ExecutionResult(validation, decodeMcpToolError(toolName, toolErrorMessage(e)));
            }
        }

        @Override
        public JsonNode finalState() {
            refreshFinalStateIfNeeded();
            return finalState;
        }

        @Override
        public String finalStateHash() {
            refreshFinalStateIfNeeded();
            return finalStateHash;
        }

        private void refreshInitialState() {
            JsonNode envelope = stateEnvelope();
            this.initialState = envelope.path("state");
            this.initialStateHash = envelope.path("stateHash").asText();
        }

        private void refreshFinalStateIfNeeded() {
            if (finalState != null && finalStateHash != null) {
                return;
            }
            JsonNode envelope = stateEnvelope();
            this.finalState = envelope.path("state");
            this.finalStateHash = envelope.path("stateHash").asText();
        }

        private JsonNode stateEnvelope() {
            McpReadResourceResult result = client.readResource(
                    "tool-lab://state/" + urlEncode(stateId) + "/" + urlEncode(taskId),
                    invocationContext);
            if (result.contents() == null || result.contents().isEmpty()) {
                throw new IllegalStateException("MCP state resource returned no contents");
            }
            McpResourceContents first = result.contents().get(0);
            if (!(first instanceof McpTextResourceContents textResource)) {
                throw new IllegalStateException("MCP state resource returned non-text content");
            }
            try {
                return mapper.readTree(textResource.text());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("MCP state resource returned invalid JSON", e);
            }
        }

        private String urlEncode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }

    private ToolCallResult decodeToolResult(String toolName, ToolExecutionResult result) {
        if (result.isError()) {
            return decodeMcpToolError(toolName, resultText(result));
        }
        Object structured = result.result();
        if (structured != null) {
            return ToolCallResult.success(toolName, mapper.valueToTree(structured));
        }
        String text = resultText(result);
        if (text == null || text.isBlank()) {
            return ToolCallResult.success(toolName, mapper.createObjectNode());
        }
        try {
            return ToolCallResult.success(toolName, mapper.readTree(text));
        } catch (JsonProcessingException e) {
            return ToolCallResult.success(toolName, mapper.createObjectNode().put("text", text));
        }
    }

    private String resultText(ToolExecutionResult result) {
        try {
            return result.resultText();
        } catch (IllegalStateException e) {
            return result.resultContents().toString();
        }
    }

    private String toolErrorMessage(ToolExecutionException e) {
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return e.getMessage();
    }

    private String writeJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to write MCP tool arguments", e);
        }
    }

    static ToolCallResult decodeMcpToolError(String toolName, String message) {
        if (message == null || message.isBlank()) {
            return ToolCallResult.failure(toolName, "MCP_TOOL_ERROR", "");
        }
        int colon = message.indexOf(':');
        if (colon > 0) {
            String candidate = message.substring(0, colon).trim();
            if (DOMAIN_ERROR_CODE.matcher(candidate).matches()) {
                return ToolCallResult.failure(toolName, candidate, message.substring(colon + 1).trim());
            }
        }
        return ToolCallResult.failure(toolName, "MCP_TOOL_ERROR", message);
    }
}
