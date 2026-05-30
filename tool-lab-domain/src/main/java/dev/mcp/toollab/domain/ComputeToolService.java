package dev.mcp.toollab.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.Hashing;
import dev.mcp.toollab.contract.ToolCallResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ComputeToolService {
    private final ComputeCatalog catalog;
    private final ModelFitCalculator fitCalculator;
    private final BudgetPolicy budgetPolicy;
    private final ObjectMapper mapper;

    public ComputeToolService() {
        this(ComputeCatalog.loadDefault(), new ModelFitCalculator(), new BudgetPolicy(), new ObjectMapper());
    }

    public ComputeToolService(
            ComputeCatalog catalog,
            ModelFitCalculator fitCalculator,
            BudgetPolicy budgetPolicy,
            ObjectMapper mapper) {
        this.catalog = catalog;
        this.fitCalculator = fitCalculator;
        this.budgetPolicy = budgetPolicy;
        this.mapper = mapper;
    }

    public ToolCallResult execute(String toolName, JsonNode arguments, ToolLabState state) {
        try {
            JsonNode result = switch (toolName) {
                case "search_instances" -> doSearchInstances(arguments);
                case "get_instance_spec" -> doInstanceSpec(arguments);
                case "get_instance_price" -> doInstancePrice(arguments);
                case "check_model_fit" -> doCheckModelFit(arguments);
                case "create_plan" -> doCreatePlan(arguments, state);
                case "allocate_budget" -> doAllocateBudget(arguments, state);
                case "reserve_capacity" -> doReserveCapacity(arguments, state);
                case "commit_plan" -> doCommitPlan(arguments, state);
                case "recommend_instance" -> doRecommendInstance(arguments);
                default -> throw new ToolLabException("UNKNOWN_TOOL", "Unknown tool: " + toolName);
            };
            return ToolCallResult.success(toolName, result);
        } catch (ToolLabException e) {
            return ToolCallResult.failure(toolName, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return ToolCallResult.failure(toolName, "TOOL_ERROR", e.getMessage());
        }
    }

    public JsonNode searchInstances(JsonNode args) {
        return doSearchInstances(args);
    }

    public JsonNode getInstanceSpec(JsonNode args) {
        return doInstanceSpec(args);
    }

    public JsonNode getInstancePrice(JsonNode args) {
        return doInstancePrice(args);
    }

    public JsonNode checkModelFit(JsonNode args) {
        return doCheckModelFit(args);
    }

    public JsonNode createPlan(JsonNode args, ToolLabState state) {
        return doCreatePlan(args, state);
    }

    public JsonNode allocateBudget(JsonNode args, ToolLabState state) {
        return doAllocateBudget(args, state);
    }

    public JsonNode reserveCapacity(JsonNode args, ToolLabState state) {
        return doReserveCapacity(args, state);
    }

    public JsonNode commitPlan(JsonNode args, ToolLabState state) {
        return doCommitPlan(args, state);
    }

    public JsonNode recommendInstance(JsonNode args) {
        return doRecommendInstance(args);
    }

    private JsonNode doSearchInstances(JsonNode args) {
        String workload = requiredText(args, "workload");
        int minMemory = optionalInt(args, "minAcceleratorMemoryGib", 0);
        Integer maxMonthlyCost = optionalInteger(args, "maxMonthlyCostCents");
        boolean requireEfa = optionalBoolean(args, "requireEfa", false);
        List<ComputeInstanceSpec> candidates = catalog.search(workload, minMemory, maxMonthlyCost, requireEfa);

        ObjectNode root = mapper.createObjectNode();
        root.put("workload", workload);
        ArrayNode array = root.putArray("candidates");
        for (ComputeInstanceSpec spec : candidates) {
            ObjectNode node = array.addObject();
            node.put("instanceType", spec.instanceType());
            node.put("acceleratorMemoryGib", spec.acceleratorMemoryGib());
            node.put("monthlyPriceCents", spec.monthlyPriceCents());
            node.put("efaInterfaces", spec.efaInterfaces());
        }
        return root;
    }

    private JsonNode doInstanceSpec(JsonNode args) {
        ComputeInstanceSpec spec = catalog.require(requiredText(args, "instanceType"));
        ObjectNode node = mapper.valueToTree(spec);
        node.put("monthlyPriceCents", spec.monthlyPriceCents());
        return node;
    }

    private JsonNode doInstancePrice(JsonNode args) {
        ComputeInstanceSpec spec = catalog.require(requiredText(args, "instanceType"));
        ObjectNode node = mapper.createObjectNode();
        node.put("instanceType", spec.instanceType());
        node.put("purchaseOption", optionalText(args, "purchaseOption", "on_demand"));
        node.put("currency", "USD");
        node.put("hourlyPriceCents", spec.hourlyPriceCents());
        node.put("monthlyHours", 730);
        node.put("monthlyPriceCents", spec.monthlyPriceCents());
        return node;
    }

    private JsonNode doCheckModelFit(JsonNode args) {
        ComputeInstanceSpec spec = catalog.require(requiredText(args, "instanceType"));
        ModelFitResult result = fitCalculator.check(
                spec,
                requiredInt(args, "modelBillionParameters"),
                requiredText(args, "precision"),
                requiredText(args, "mode"));
        return mapper.valueToTree(result);
    }

    private JsonNode doCreatePlan(JsonNode args, ToolLabState state) {
        String idempotencyKey = requiredText(args, "idempotencyKey");
        String scopedKey = "create_plan:" + state.taskId() + ":" + idempotencyKey;
        String existingPlanId = state.planForIdempotencyKey(scopedKey).orElse(null);
        if (existingPlanId != null) {
            return planNode(state.requirePlan(existingPlanId), true);
        }

        String instanceType = requiredText(args, "instanceType");
        ComputeInstanceSpec spec = catalog.require(instanceType);
        int units = optionalInt(args, "units", 1);
        String planId = "plan-" + Hashing.shortSha256(state.taskId() + "|" + idempotencyKey, 12);
        ComputePlan plan = new ComputePlan(
                planId,
                requiredText(args, "projectId"),
                instanceType,
                requiredInt(args, "modelBillionParameters"),
                requiredText(args, "precision"),
                requiredText(args, "mode"),
                units,
                spec.monthlyPriceCents() * units);
        state.putPlan(plan);
        state.rememberPlanIdempotency(scopedKey, planId);
        return planNode(plan, false);
    }

    private JsonNode doAllocateBudget(JsonNode args, ToolLabState state) {
        ComputePlan plan = state.requirePlan(requiredText(args, "planId"));
        String projectId = requiredText(args, "projectId");
        if (!plan.projectId().equals(projectId)) {
            throw new ToolLabException("PROJECT_MISMATCH", "Plan does not belong to project: " + projectId);
        }
        if (plan.budgetAllocated()) {
            ObjectNode node = planNode(plan, true);
            node.put("alreadyAllocated", true);
            node.put("remainingBudgetCents", state.remainingBudgetCents(projectId));
            return node;
        }
        if (!budgetPolicy.canAllocate(state, projectId, plan.monthlyCostCents())) {
            throw new ToolLabException("BUDGET_EXCEEDED", "Insufficient project budget for plan " + plan.planId());
        }
        state.decrementBudget(projectId, plan.monthlyCostCents());
        plan.markBudgetAllocated();
        ObjectNode node = planNode(plan, false);
        node.put("allocatedBudgetCents", plan.monthlyCostCents());
        node.put("remainingBudgetCents", state.remainingBudgetCents(projectId));
        return node;
    }

    private JsonNode doReserveCapacity(JsonNode args, ToolLabState state) {
        ComputePlan plan = state.requirePlan(requiredText(args, "planId"));
        if (plan.capacityReserved()) {
            ObjectNode node = planNode(plan, true);
            node.put("alreadyReserved", true);
            node.put("remainingCapacity", state.capacityRemaining(plan.instanceType()));
            return node;
        }
        if (state.capacityRemaining(plan.instanceType()) < plan.units()) {
            throw new ToolLabException("CAPACITY_UNAVAILABLE", "Insufficient capacity for " + plan.instanceType());
        }
        state.decrementCapacity(plan.instanceType(), plan.units());
        plan.markCapacityReserved();
        ObjectNode node = planNode(plan, false);
        node.put("reservedUnits", plan.units());
        node.put("remainingCapacity", state.capacityRemaining(plan.instanceType()));
        return node;
    }

    private JsonNode doCommitPlan(JsonNode args, ToolLabState state) {
        ComputePlan plan = state.requirePlan(requiredText(args, "planId"));
        if (plan.status() == PlanStatus.COMMITTED) {
            ObjectNode node = planNode(plan, true);
            node.put("alreadyCommitted", true);
            return node;
        }
        if (!plan.budgetAllocated() || !plan.capacityReserved()) {
            throw new ToolLabException("PLAN_NOT_READY", "Plan requires budget and capacity before commit");
        }
        plan.markCommitted();
        ObjectNode node = planNode(plan, false);
        node.put("committed", true);
        return node;
    }

    private JsonNode doRecommendInstance(JsonNode args) {
        List<String> candidates = new ArrayList<>();
        JsonNode candidateNode = args.path("candidateInstanceTypes");
        if (!candidateNode.isArray()) {
            throw new ToolLabException("INVALID_ARGUMENT", "candidateInstanceTypes must be an array");
        }
        candidateNode.forEach(item -> candidates.add(item.asText()));
        int modelSize = requiredInt(args, "modelBillionParameters");
        String precision = requiredText(args, "precision");
        String mode = requiredText(args, "mode");
        String optimizeFor = requiredText(args, "optimizeFor");
        Integer maxMonthlyCost = optionalInteger(args, "maxMonthlyCostCents");
        boolean requireEfa = optionalBoolean(args, "requireEfa", false);

        List<RecommendationCandidate> viable = candidates.stream()
                .map(catalog::require)
                .filter(spec -> !requireEfa || spec.hasEfa())
                .filter(spec -> maxMonthlyCost == null || spec.monthlyPriceCents() <= maxMonthlyCost)
                .map(spec -> new RecommendationCandidate(spec, fitCalculator.check(spec, modelSize, precision, mode)))
                .filter(candidate -> candidate.fit().fits())
                .toList();
        if (viable.isEmpty()) {
            ObjectNode rejected = mapper.createObjectNode();
            rejected.put("recommendedInstanceType", (String) null);
            rejected.put("reason", "no_viable_candidate");
            rejected.put("fits", false);
            return rejected;
        }

        Comparator<RecommendationCandidate> comparator = switch (optimizeFor) {
            case "cheapest" -> Comparator
                    .comparingInt((RecommendationCandidate c) -> c.spec().monthlyPriceCents())
                    .thenComparing(c -> c.spec().instanceType());
            case "most_memory" -> Comparator
                    .comparingInt((RecommendationCandidate c) -> c.spec().acceleratorMemoryGib())
                    .reversed()
                    .thenComparing(c -> c.spec().instanceType());
            default -> throw new ToolLabException("INVALID_OPTIMIZE_FOR", "Unsupported optimizeFor: " + optimizeFor);
        };
        RecommendationCandidate selected = viable.stream().sorted(comparator).findFirst().orElseThrow();

        ObjectNode node = mapper.createObjectNode();
        node.put("recommendedInstanceType", selected.spec().instanceType());
        node.put("reason", optimizeFor);
        node.put("fits", true);
        node.put("requiredAcceleratorMemoryGib", selected.fit().requiredAcceleratorMemoryGib());
        node.put("availableAcceleratorMemoryGib", selected.fit().availableAcceleratorMemoryGib());
        node.put("memoryHeadroomGib", selected.fit().memoryHeadroomGib());
        node.put("monthlyPriceCents", selected.spec().monthlyPriceCents());
        node.put("efaInterfaces", selected.spec().efaInterfaces());
        return node;
    }

    private ObjectNode planNode(ComputePlan plan, boolean idempotentReplay) {
        ObjectNode node = mapper.createObjectNode();
        node.put("planId", plan.planId());
        node.put("projectId", plan.projectId());
        node.put("instanceType", plan.instanceType());
        node.put("modelBillionParameters", plan.modelBillionParameters());
        node.put("precision", plan.precision());
        node.put("mode", plan.mode());
        node.put("units", plan.units());
        node.put("monthlyCostCents", plan.monthlyCostCents());
        node.put("status", plan.status().name());
        node.put("budgetAllocated", plan.budgetAllocated());
        node.put("capacityReserved", plan.capacityReserved());
        node.put("idempotentReplay", idempotentReplay);
        return node;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new ToolLabException("MISSING_PARAMETER", "Missing required text field: " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asText();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new ToolLabException("MISSING_PARAMETER", "Missing required integer field: " + field);
        }
        return value.asInt();
    }

    private static int optionalInt(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asInt();
    }

    private static Integer optionalInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static boolean optionalBoolean(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? defaultValue : value.asBoolean();
    }

    private record RecommendationCandidate(ComputeInstanceSpec spec, ModelFitResult fit) {
    }
}
