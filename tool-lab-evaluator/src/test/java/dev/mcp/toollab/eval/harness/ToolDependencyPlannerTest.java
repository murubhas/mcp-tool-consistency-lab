package dev.mcp.toollab.eval.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.eval.model.ToolCall;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolDependencyPlannerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mutatingCallsInSameTurnAreSerializedByCallId() throws Exception {
        ToolDependencyPlanner planner = new ToolDependencyPlanner(ToolSchemaRegistry.loadDefault(mapper));
        ToolCall later = new ToolCall("call-b", "create_plan", mapper.readTree("{}"));
        ToolCall earlier = new ToolCall("call-a", "commit_plan", mapper.readTree("{}"));

        List<ToolCall> scheduled = planner.schedule(List.of(later, earlier));

        assertEquals(List.of("call-a", "call-b"), scheduled.stream().map(ToolCall::id).toList());
        assertEquals("serial-1-001", planner.dependencyGroup(scheduled.get(0), 1, 1));
        assertEquals("serial-1-002", planner.dependencyGroup(scheduled.get(1), 1, 2));
    }

    @Test
    void readOnlyCallsRemainParallelGroup() throws Exception {
        ToolDependencyPlanner planner = new ToolDependencyPlanner(ToolSchemaRegistry.loadDefault(mapper));
        ToolCall first = new ToolCall("call-a", "get_instance_spec", mapper.readTree("{}"));

        assertEquals("parallel-3", planner.dependencyGroup(first, 3, 1));
    }
}
