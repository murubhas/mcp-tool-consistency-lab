package dev.mcp.toollab.domain;

public final class BudgetPolicy {
    public boolean canAllocate(ToolLabState state, String projectId, int monthlyCostCents) {
        return state.remainingBudgetCents(projectId) >= monthlyCostCents;
    }
}
