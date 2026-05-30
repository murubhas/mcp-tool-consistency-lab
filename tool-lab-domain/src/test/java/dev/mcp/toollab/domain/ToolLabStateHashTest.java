package dev.mcp.toollab.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ToolLabStateHashTest {
    @Test
    void defaultStateHashIsStableForSameTask() {
        ToolLabState first = ToolLabStateFactory.defaultState("task-1");
        ToolLabState second = ToolLabStateFactory.defaultState("task-1");

        assertEquals(CanonicalState.canonicalStateHash(first), CanonicalState.canonicalStateHash(second));
    }

    @Test
    void committedStateChangesHash() {
        ToolLabState state = ToolLabStateFactory.defaultState("task-2");
        String before = CanonicalState.canonicalStateHash(state);
        ComputeToolService tools = new ComputeToolService();
        tools.execute("create_plan", Json.arg("""
                {
                  "projectId": "proj-alpha",
                  "instanceType": "g7e.2xlarge",
                  "modelBillionParameters": 34,
                  "precision": "fp8",
                  "mode": "inference",
                  "idempotencyKey": "hash-test"
                }
                """), state);

        assertNotEquals(before, CanonicalState.canonicalStateHash(state));
    }
}
