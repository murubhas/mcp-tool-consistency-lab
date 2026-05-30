package dev.mcp.toollab.eval.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.contract.Hashing;
import dev.mcp.toollab.contract.ToolCallResult;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ToolSchemaValidator;
import dev.mcp.toollab.eval.schema.ValidationResult;

public final class FakeToolExecutionClient implements ToolExecutionClient {
    public static final String MODE = McpHttpToolExecutionClient.MODE;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaValidator validator;

    public FakeToolExecutionClient(ToolSchemaValidator validator) {
        this.validator = validator;
    }

    @Override
    public String mode() {
        return MODE;
    }

    @Override
    public ToolExecutionSession startTask(String runId, EvalTask task) {
        return new FakeSession(task.taskId());
    }

    private final class FakeSession implements ToolExecutionSession {
        private final String stateId;
        private final JsonNode state;
        private final String stateHash;

        private FakeSession(String taskId) {
            this.stateId = taskId;
            ObjectNode stateNode = mapper.createObjectNode();
            stateNode.put("taskId", taskId);
            stateNode.put("mode", "fake-local-mcp-http");
            this.state = stateNode;
            this.stateHash = Hashing.sha256(CanonicalJson.writeCanonical(state));
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
            return state;
        }

        @Override
        public String initialStateHash() {
            return stateHash;
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
            if ("commit_plan".equals(toolName) && "plan-missing".equals(arguments.path("planId").asText())) {
                return new ExecutionResult(validation, ToolCallResult.failure(
                        toolName,
                        "UNKNOWN_PLAN",
                        "Unknown plan: plan-missing"));
            }
            return new ExecutionResult(validation, ToolCallResult.success(toolName, fakeResult(toolName, arguments)));
        }

        @Override
        public JsonNode finalState() {
            return state;
        }

        @Override
        public String finalStateHash() {
            return stateHash;
        }

        private JsonNode fakeResult(String toolName, JsonNode arguments) {
            ObjectNode result = mapper.createObjectNode();
            result.put("toolName", toolName);
            if (arguments.has("instanceType")) {
                result.put("instanceType", arguments.path("instanceType").asText());
            }
            if ("recommend_instance".equals(toolName)) {
                result.put("recommendedInstanceType", "p5.48xlarge");
            }
            return result;
        }
    }
}
