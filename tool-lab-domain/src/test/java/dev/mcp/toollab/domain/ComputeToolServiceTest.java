package dev.mcp.toollab.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeToolServiceTest {
    @Test
    void recommendsCheapestFittingInferenceCandidate() {
        ComputeToolService tools = new ComputeToolService();

        var result = tools.execute("recommend_instance", Json.arg("""
                {
                  "candidateInstanceTypes": ["g6e.xlarge", "g7e.2xlarge"],
                  "modelBillionParameters": 34,
                  "precision": "fp8",
                  "mode": "inference",
                  "optimizeFor": "cheapest"
                }
                """), ToolLabStateFactory.defaultState("task"));

        assertTrue(result.success());
        assertEquals("g6e.xlarge", result.result().path("recommendedInstanceType").asText());
    }

    @Test
    void serializesBudgetCapacityAndCommitState() {
        ComputeToolService tools = new ComputeToolService();
        ToolLabState state = ToolLabStateFactory.defaultState("compute.state.budget.002");

        var create = tools.execute("create_plan", Json.arg("""
                {
                  "projectId": "proj-alpha",
                  "instanceType": "g7e.2xlarge",
                  "modelBillionParameters": 34,
                  "precision": "fp8",
                  "mode": "inference",
                  "units": 1,
                  "idempotencyKey": "state-002"
                }
                """), state);
        String planId = create.result().path("planId").asText();
        tools.execute("allocate_budget", Json.arg("""
                {"planId": "%s", "projectId": "proj-alpha", "idempotencyKey": "budget-002"}
                """.formatted(planId)), state);
        tools.execute("reserve_capacity", Json.arg("""
                {"planId": "%s", "idempotencyKey": "capacity-002"}
                """.formatted(planId)), state);
        var commit = tools.execute("commit_plan", Json.arg("""
                {"planId": "%s", "idempotencyKey": "commit-002"}
                """.formatted(planId)), state);

        assertTrue(commit.success());
        assertEquals("COMMITTED", state.requirePlan(planId).status().name());
    }
}
