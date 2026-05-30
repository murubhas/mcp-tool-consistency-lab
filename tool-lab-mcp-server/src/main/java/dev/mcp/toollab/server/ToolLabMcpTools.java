package dev.mcp.toollab.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.ToolCallResult;
import dev.mcp.toollab.domain.CanonicalState;
import dev.mcp.toollab.domain.ComputeToolService;
import dev.mcp.toollab.domain.ToolLabException;
import dev.mcp.toollab.domain.ToolLabState;
import dev.mcp.toollab.domain.ToolLabStateFactory;
import dev.mcp.toollab.server.telemetry.ToolCallRecorder;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.Meta;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.Tool.Annotations;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolGuardrails;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@ApplicationScoped
public class ToolLabMcpTools {
    static final String DEFAULT_MCP_STATE_ID = McpStateIds.DEFAULT_MCP_STATE_ID;
    static final String META_STATE_ID = McpStateIds.META_STATE_ID;
    static final String META_TASK_ID = McpStateIds.META_TASK_ID;

    private final ComputeToolService tools;
    private final ToolCallRecorder recorder;
    private final ObjectMapper mapper;
    private final Map<String, ToolLabState> states = new ConcurrentHashMap<>();

    @Inject
    public ToolLabMcpTools(ComputeToolService tools, ToolCallRecorder recorder, ObjectMapper mapper) {
        this.tools = tools;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    @Tool(
            description = "Search deterministic compute instances by workload, accelerator memory, budget, and EFA requirement.",
            structuredContent = true,
            annotations = @Annotations(
                    title = "Search Instances",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public JsonNode search_instances(
            @ToolArg(description = "Supported workload such as llm_inference or fine_tuning") String workload,
            @ToolArg(description = "Minimum accelerator memory in GiB", defaultValue = "0")
                    Integer minAcceleratorMemoryGib,
            @ToolArg(description = "Maximum monthly cost in cents", required = false) Integer maxMonthlyCostCents,
            @ToolArg(description = "Whether EFA support is required", defaultValue = "false") Boolean requireEfa) {
        ObjectNode args = mapper.createObjectNode();
        put(args, "workload", workload);
        put(args, "minAcceleratorMemoryGib", minAcceleratorMemoryGib);
        put(args, "maxMonthlyCostCents", maxMonthlyCostCents);
        put(args, "requireEfa", requireEfa);
        return executeStructured("search_instances", () -> tools.searchInstances(args));
    }

    @Tool(
            description = "Get deterministic hardware and pricing specs for one compute instance type.",
            structuredContent = true,
            annotations = @Annotations(
                    title = "Get Instance Spec",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    @ToolGuardrails(output = RecommendationEvidenceOutputGuardrail.class)
    public JsonNode get_instance_spec(
            @ToolArg(description = "Instance type, for example p5.48xlarge") String instanceType) {
        ObjectNode args = mapper.createObjectNode();
        put(args, "instanceType", instanceType);
        return executeStructured("get_instance_spec", () -> tools.getInstanceSpec(args));
    }

    @Tool(
            description = "Get deterministic on-demand price information for one compute instance type.",
            structuredContent = true,
            annotations = @Annotations(
                    title = "Get Instance Price",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    @ToolGuardrails(output = RecommendationEvidenceOutputGuardrail.class)
    public JsonNode get_instance_price(
            @ToolArg(description = "Instance type, for example g7e.2xlarge") String instanceType,
            @ToolArg(description = "Purchase option", defaultValue = "on_demand") String purchaseOption) {
        ObjectNode args = mapper.createObjectNode();
        put(args, "instanceType", instanceType);
        put(args, "purchaseOption", purchaseOption);
        return executeStructured("get_instance_price", () -> tools.getInstancePrice(args));
    }

    @Tool(
            description = "Check whether a model fits on an instance for inference or fine-tuning.",
            structuredContent = true,
            annotations = @Annotations(
                    title = "Check Model Fit",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    @ToolGuardrails(output = RecommendationEvidenceOutputGuardrail.class)
    public JsonNode check_model_fit(
            @ToolArg(description = "Instance type") String instanceType,
            @ToolArg(description = "Model size in billions of parameters") Integer modelBillionParameters,
            @ToolArg(description = "Precision such as bf16, fp8, or int4") String precision,
            @ToolArg(description = "Mode such as inference or fine_tuning") String mode) {
        ObjectNode args = mapper.createObjectNode();
        put(args, "instanceType", instanceType);
        put(args, "modelBillionParameters", modelBillionParameters);
        put(args, "precision", precision);
        put(args, "mode", mode);
        return executeStructured("check_model_fit", () -> tools.checkModelFit(args));
    }

    @Tool(
            description = "Create an idempotent compute plan in the server's deterministic MCP state.",
            structuredContent = true,
            annotations = @Annotations(
                    title = "Create Plan",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public JsonNode create_plan(
            @ToolArg(description = "Project ID") String projectId,
            @ToolArg(description = "Instance type") String instanceType,
            @ToolArg(description = "Model size in billions of parameters") Integer modelBillionParameters,
            @ToolArg(description = "Precision such as bf16, fp8, or int4") String precision,
            @ToolArg(description = "Mode such as inference or fine_tuning") String mode,
            @ToolArg(description = "Number of units", defaultValue = "1") Integer units,
            @ToolArg(description = "Caller-provided idempotency key") String idempotencyKey,
            Meta meta,
            McpConnection connection) {
        ObjectNode args = mapper.createObjectNode();
        put(args, "projectId", projectId);
        put(args, "instanceType", instanceType);
        put(args, "modelBillionParameters", modelBillionParameters);
        put(args, "precision", precision);
        put(args, "mode", mode);
        put(args, "units", units);
        put(args, "idempotencyKey", idempotencyKey);
        return executeStructured("create_plan", () -> tools.createPlan(args, state(meta, connection)));
    }

    @Tool(
            description = "Allocate project budget for an existing compute plan in deterministic MCP state.",
            structuredContent = true,
            annotations = @Annotations(
                    title = "Allocate Budget",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public JsonNode allocate_budget(
            @ToolArg(description = "Plan ID returned by create_plan") String planId,
            @ToolArg(description = "Project ID that owns the plan") String projectId,
            @ToolArg(description = "Caller-provided idempotency key") String idempotencyKey,
            Meta meta,
            McpConnection connection) {
        ObjectNode args = mapper.createObjectNode();
        put(args, "planId", planId);
        put(args, "projectId", projectId);
        put(args, "idempotencyKey", idempotencyKey);
        return executeStructured("allocate_budget", () -> tools.allocateBudget(args, state(meta, connection)));
    }

    @Tool(
            description = "Reserve deterministic capacity for an existing compute plan.",
            structuredContent = true,
            annotations = @Annotations(
                    title = "Reserve Capacity",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public JsonNode reserve_capacity(
            @ToolArg(description = "Plan ID returned by create_plan") String planId,
            @ToolArg(description = "Caller-provided idempotency key") String idempotencyKey,
            Meta meta,
            McpConnection connection) {
        ObjectNode args = mapper.createObjectNode();
        put(args, "planId", planId);
        put(args, "idempotencyKey", idempotencyKey);
        return executeStructured("reserve_capacity", () -> tools.reserveCapacity(args, state(meta, connection)));
    }

    @Tool(
            description = "Commit a compute plan after budget and capacity are allocated.",
            structuredContent = true,
            annotations = @Annotations(
                    title = "Commit Plan",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public JsonNode commit_plan(
            @ToolArg(description = "Plan ID returned by create_plan") String planId,
            @ToolArg(description = "Caller-provided idempotency key") String idempotencyKey,
            Meta meta,
            McpConnection connection) {
        ObjectNode args = mapper.createObjectNode();
        put(args, "planId", planId);
        put(args, "idempotencyKey", idempotencyKey);
        return executeStructured("commit_plan", () -> tools.commitPlan(args, state(meta, connection)));
    }

    @Tool(
            description = "Recommend the best fitting deterministic compute instance from candidates.",
            structuredContent = true,
            annotations = @Annotations(
                    title = "Recommend Instance",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    @ToolGuardrails(input = RecommendationPolicyInputGuardrail.class)
    public JsonNode recommend_instance(
            @ToolArg(description = "Candidate instance types") List<String> candidateInstanceTypes,
            @ToolArg(description = "Model size in billions of parameters") Integer modelBillionParameters,
            @ToolArg(description = "Precision such as bf16, fp8, or int4") String precision,
            @ToolArg(description = "Mode such as inference or fine_tuning") String mode,
            @ToolArg(description = "Optimization target such as cheapest or most_memory") String optimizeFor,
            @ToolArg(description = "Maximum monthly cost in cents", required = false) Integer maxMonthlyCostCents,
            @ToolArg(description = "Whether EFA support is required", defaultValue = "false") Boolean requireEfa) {
        ObjectNode args = mapper.createObjectNode();
        ArrayNode candidates = args.putArray("candidateInstanceTypes");
        if (candidateInstanceTypes != null) {
            candidateInstanceTypes.forEach(candidates::add);
        }
        put(args, "modelBillionParameters", modelBillionParameters);
        put(args, "precision", precision);
        put(args, "mode", mode);
        put(args, "optimizeFor", optimizeFor);
        put(args, "maxMonthlyCostCents", maxMonthlyCostCents);
        put(args, "requireEfa", requireEfa);
        return executeStructured("recommend_instance", () -> tools.recommendInstance(args));
    }

    public ToolLabState reset(String taskId) {
        ToolLabState state = ToolLabStateFactory.defaultState(taskId);
        states.put(taskId, state);
        return state;
    }

    public ToolLabState state(String taskId) {
        return states.computeIfAbsent(taskId, ToolLabStateFactory::defaultState);
    }

    ToolLabState state(String stateId, String taskId) {
        return states.computeIfAbsent(stateId, ignored -> ToolLabStateFactory.defaultState(taskId));
    }

    public String stateHash(String taskId) {
        return CanonicalState.canonicalStateHash(state(taskId));
    }

    public JsonNode canonicalState(String taskId) {
        return CanonicalState.canonicalState(state(taskId));
    }

    public ToolCallResult execute(String taskId, String toolName, JsonNode arguments) {
        ToolLabState state = state(taskId);
        long started = System.nanoTime();
        ToolCallResult result = tools.execute(toolName, arguments, state);
        recorder.record(toolName, result.success(), System.nanoTime() - started);
        return result;
    }

    private ToolLabState state(Meta meta, McpConnection connection) {
        String stateId = McpStateIds.stateId(meta, connection);
        String taskId = McpStateIds.taskId(meta, stateId);
        return state(stateId, taskId);
    }

    @ResourceTemplate(
            uriTemplate = "tool-lab://state/{stateId}/{taskId}",
            description = "Benchmark state snapshot for MCP-wire evaluator runs.",
            mimeType = "application/json")
    public TextResourceContents tool_lab_state_snapshot(String stateId, String taskId) {
        ObjectNode root = mapper.createObjectNode();
        ToolLabState state = state(stateId, taskId);
        root.put("stateId", stateId);
        root.put("taskId", taskId);
        root.put("stateHash", CanonicalState.canonicalStateHash(state));
        root.set("state", CanonicalState.canonicalState(state));
        try {
            return new TextResourceContents(
                    "tool-lab://state/" + stateId + "/" + taskId,
                    mapper.writeValueAsString(root),
                    "application/json");
        } catch (Exception e) {
            throw new ToolCallException("STATE_SNAPSHOT_ERROR: " + e.getMessage());
        }
    }

    private JsonNode executeStructured(String toolName, Supplier<JsonNode> action) {
        long started = System.nanoTime();
        try {
            JsonNode result = action.get();
            recorder.record(toolName, true, System.nanoTime() - started);
            return result;
        } catch (ToolLabException e) {
            recorder.record(toolName, false, System.nanoTime() - started);
            throw new ToolCallException(e.code() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            recorder.record(toolName, false, System.nanoTime() - started);
            throw new ToolCallException("TOOL_ERROR: " + e.getMessage());
        }
    }

    private void put(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private void put(ObjectNode node, String field, Integer value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private void put(ObjectNode node, String field, Boolean value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
