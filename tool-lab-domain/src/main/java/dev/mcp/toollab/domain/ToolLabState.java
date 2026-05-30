package dev.mcp.toollab.domain;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class ToolLabState {
    private final String taskId;
    private final TreeMap<String, Integer> budgetsCents;
    private final TreeMap<String, Integer> capacityRemaining;
    private final TreeMap<String, ComputePlan> plans = new TreeMap<>();
    private final TreeMap<String, String> planIdempotency = new TreeMap<>();

    public ToolLabState(
            String taskId,
            Map<String, Integer> budgetsCents,
            Map<String, Integer> capacityRemaining) {
        this.taskId = taskId;
        this.budgetsCents = new TreeMap<>(budgetsCents);
        this.capacityRemaining = new TreeMap<>(capacityRemaining);
    }

    public String taskId() {
        return taskId;
    }

    public int remainingBudgetCents(String projectId) {
        Integer value = budgetsCents.get(projectId);
        if (value == null) {
            throw new ToolLabException("UNKNOWN_PROJECT", "Unknown project: " + projectId);
        }
        return value;
    }

    public int capacityRemaining(String instanceType) {
        Integer value = capacityRemaining.get(instanceType);
        if (value == null) {
            throw new ToolLabException("UNKNOWN_INSTANCE", "No capacity pool for: " + instanceType);
        }
        return value;
    }

    public void decrementBudget(String projectId, int cents) {
        budgetsCents.put(projectId, remainingBudgetCents(projectId) - cents);
    }

    public void decrementCapacity(String instanceType, int units) {
        capacityRemaining.put(instanceType, capacityRemaining(instanceType) - units);
    }

    public Optional<String> planForIdempotencyKey(String idempotencyKey) {
        return Optional.ofNullable(planIdempotency.get(idempotencyKey));
    }

    public void rememberPlanIdempotency(String idempotencyKey, String planId) {
        planIdempotency.put(idempotencyKey, planId);
    }

    public Optional<ComputePlan> findPlan(String planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    public ComputePlan requirePlan(String planId) {
        return findPlan(planId).orElseThrow(() -> new ToolLabException(
                "UNKNOWN_PLAN",
                "Unknown plan: " + planId));
    }

    public void putPlan(ComputePlan plan) {
        plans.put(plan.planId(), plan);
    }

    public Map<String, Integer> budgetsCents() {
        return Collections.unmodifiableMap(budgetsCents);
    }

    public Map<String, Integer> capacityRemaining() {
        return Collections.unmodifiableMap(capacityRemaining);
    }

    public Map<String, ComputePlan> plans() {
        return Collections.unmodifiableMap(plans);
    }
}
