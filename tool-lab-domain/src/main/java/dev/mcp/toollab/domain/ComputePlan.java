package dev.mcp.toollab.domain;

public final class ComputePlan {
    private final String planId;
    private final String projectId;
    private final String instanceType;
    private final int modelBillionParameters;
    private final String precision;
    private final String mode;
    private final int units;
    private final int monthlyCostCents;
    private PlanStatus status;
    private boolean budgetAllocated;
    private boolean capacityReserved;

    public ComputePlan(
            String planId,
            String projectId,
            String instanceType,
            int modelBillionParameters,
            String precision,
            String mode,
            int units,
            int monthlyCostCents) {
        this.planId = planId;
        this.projectId = projectId;
        this.instanceType = instanceType;
        this.modelBillionParameters = modelBillionParameters;
        this.precision = precision;
        this.mode = mode;
        this.units = units;
        this.monthlyCostCents = monthlyCostCents;
        this.status = PlanStatus.CREATED;
    }

    public String planId() {
        return planId;
    }

    public String projectId() {
        return projectId;
    }

    public String instanceType() {
        return instanceType;
    }

    public int modelBillionParameters() {
        return modelBillionParameters;
    }

    public String precision() {
        return precision;
    }

    public String mode() {
        return mode;
    }

    public int units() {
        return units;
    }

    public int monthlyCostCents() {
        return monthlyCostCents;
    }

    public PlanStatus status() {
        return status;
    }

    public boolean budgetAllocated() {
        return budgetAllocated;
    }

    public boolean capacityReserved() {
        return capacityReserved;
    }

    public void markBudgetAllocated() {
        this.budgetAllocated = true;
        if (status == PlanStatus.CREATED) {
            status = PlanStatus.BUDGET_ALLOCATED;
        }
    }

    public void markCapacityReserved() {
        this.capacityReserved = true;
        if (status == PlanStatus.CREATED || status == PlanStatus.BUDGET_ALLOCATED) {
            status = PlanStatus.CAPACITY_RESERVED;
        }
    }

    public void markCommitted() {
        this.status = PlanStatus.COMMITTED;
    }
}
