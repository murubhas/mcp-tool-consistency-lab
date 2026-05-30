package dev.mcp.toollab.eval.harness;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mcp.toollab.contract.ToolCallResult;
import dev.mcp.toollab.eval.EvalTask;
import dev.mcp.toollab.eval.schema.ValidationResult;

public interface ToolExecutionClient extends AutoCloseable {
    String mode();

    ToolExecutionSession startTask(String runId, EvalTask task);

    @Override
    default void close() {
    }

    record ExecutionResult(ValidationResult validation, ToolCallResult result) {
    }

    interface ToolExecutionSession extends AutoCloseable {
        String mode();

        String stateId();

        JsonNode initialState();

        String initialStateHash();

        ExecutionResult execute(String toolName, JsonNode arguments);

        JsonNode finalState();

        String finalStateHash();

        @Override
        default void close() {
        }
    }
}
