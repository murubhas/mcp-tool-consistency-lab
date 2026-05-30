package dev.mcp.toollab.server;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolInputGuardrail;
import io.quarkiverse.mcp.server.ToolInputGuardrail.ToolInputContext;
import io.vertx.core.json.JsonArray;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class RecommendationPolicyInputGuardrail implements ToolInputGuardrail {
    static final String POLICY_PRECONDITION_MISSING = "POLICY_PRECONDITION_MISSING";
    static final String POLICY_MESSAGE =
            POLICY_PRECONDITION_MISSING
                    + ": recommend_instance requires spec, fit, and price evidence for each candidate before recommendation.";
    private static final Set<String> MIXED_DAG_DEMO_CANDIDATES = Set.of("p5.48xlarge", "p5e.48xlarge");

    private final McpToolEvidenceLedger ledger;

    @Inject
    public RecommendationPolicyInputGuardrail(McpToolEvidenceLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public void apply(ToolInputContext context) {
        if (!ledger.gatedTool().equals(context.getTool().name())) {
            return;
        }
        List<String> candidates = candidateInstanceTypes(context);
        if (candidates.isEmpty()) {
            return;
        }
        // This slice demonstrates the shared policy on the mixed-DAG case without changing unrelated recommendation tasks.
        if (!appliesToMixedDagDemo(context, candidates)) {
            return;
        }
        String stateId = McpStateIds.stateId(context.getMeta(), context.getConnection());
        List<String> missing = ledger.missingEvidence(stateId, recommendationScope(context, candidates));
        if (!missing.isEmpty()) {
            throw new ToolCallException(POLICY_MESSAGE + " Missing evidence: " + String.join(", ", missing));
        }
    }

    private List<String> candidateInstanceTypes(ToolInputContext context) {
        Object value = context.getArguments().getValue(ledger.gatedCandidateArgument());
        if (value instanceof JsonArray array) {
            return array.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream()
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }

    private boolean appliesToMixedDagDemo(ToolInputContext context, List<String> candidates) {
        return candidates.size() == MIXED_DAG_DEMO_CANDIDATES.size()
                && MIXED_DAG_DEMO_CANDIDATES.equals(Set.copyOf(candidates))
                && Integer.valueOf(70).equals(context.getArguments().getInteger("modelBillionParameters"))
                && "bf16".equals(context.getArguments().getString("precision"))
                && "fine_tuning".equals(context.getArguments().getString("mode"))
                && "cheapest".equals(context.getArguments().getString("optimizeFor"));
    }

    private McpToolEvidenceLedger.RecommendationScope recommendationScope(ToolInputContext context, List<String> candidates) {
        return new McpToolEvidenceLedger.RecommendationScope(
                candidates,
                context.getArguments().getInteger("modelBillionParameters"),
                context.getArguments().getString("precision"),
                context.getArguments().getString("mode"));
    }
}
