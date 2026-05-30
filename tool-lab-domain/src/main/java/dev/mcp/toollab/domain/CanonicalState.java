package dev.mcp.toollab.domain;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.contract.Hashing;

import java.util.Comparator;

public final class CanonicalState {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalState() {
    }

    public static ObjectNode canonicalState(ToolLabState state) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("taskId", state.taskId());

        ObjectNode budgets = root.putObject("budgetsCents");
        state.budgetsCents().forEach(budgets::put);

        ObjectNode capacity = root.putObject("capacityRemaining");
        state.capacityRemaining().forEach(capacity::put);

        ArrayNode plans = root.putArray("plans");
        state.plans().values().stream()
                .sorted(Comparator.comparing(ComputePlan::planId))
                .forEach(plan -> {
                    ObjectNode node = plans.addObject();
                    node.put("planId", plan.planId());
                    node.put("projectId", plan.projectId());
                    node.put("instanceType", plan.instanceType());
                    node.put("modelBillionParameters", plan.modelBillionParameters());
                    node.put("precision", plan.precision());
                    node.put("mode", plan.mode());
                    node.put("units", plan.units());
                    node.put("monthlyCostCents", plan.monthlyCostCents());
                    node.put("budgetAllocated", plan.budgetAllocated());
                    node.put("capacityReserved", plan.capacityReserved());
                    node.put("status", plan.status().name());
                });
        return root;
    }

    public static String canonicalStateJson(ToolLabState state) {
        return CanonicalJson.writeCanonical(canonicalState(state));
    }

    public static String canonicalStateHash(ToolLabState state) {
        return Hashing.sha256(canonicalStateJson(state));
    }
}
