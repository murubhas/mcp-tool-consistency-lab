package dev.mcp.toollab.eval.harness;

import dev.mcp.toollab.eval.model.ToolCall;
import dev.mcp.toollab.eval.schema.ToolDefinition;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class ToolDependencyPlanner {
    private final ToolSchemaRegistry registry;

    public ToolDependencyPlanner(ToolSchemaRegistry registry) {
        this.registry = registry;
    }

    public String dependencyGroup(ToolCall call, int step) {
        return dependencyGroup(call, step, 0);
    }

    public String dependencyGroup(ToolCall call, int step, int serialIndex) {
        ToolDefinition definition = registry.find(call.name()).orElse(null);
        if (definition != null && definition.readOnly()) {
            return "parallel-" + step;
        }
        return "serial-" + step + "-" + String.format("%03d", serialIndex);
    }

    public String sideEffects(String toolName) {
        return registry.find(toolName).map(ToolDefinition::sideEffects).orElse("unknown");
    }

    public boolean readOnly(String toolName) {
        return registry.find(toolName).map(ToolDefinition::readOnly).orElse(false);
    }

    public List<ToolCall> schedule(List<ToolCall> calls) {
        List<ToolCall> readOnly = calls.stream()
                .filter(call -> readOnly(call.name()))
                .sorted(Comparator.comparing(ToolCall::id))
                .toList();
        List<ToolCall> mutating = calls.stream()
                .filter(call -> !readOnly(call.name()))
                .sorted(Comparator.comparing(ToolCall::id))
                .toList();
        return Stream.concat(readOnly.stream(), mutating.stream()).toList();
    }
}
