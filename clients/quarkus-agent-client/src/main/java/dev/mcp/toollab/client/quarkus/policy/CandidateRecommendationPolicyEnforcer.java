package dev.mcp.toollab.client.quarkus.policy;

import dev.mcp.toollab.contract.ToolPolicy;
import dev.mcp.toollab.contract.ToolPolicyCatalog;
import dev.mcp.toollab.contract.ToolPolicyEvidenceRequirement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Singleton
public final class CandidateRecommendationPolicyEnforcer {
    private final ToolPolicy policy;

    @Inject
    public CandidateRecommendationPolicyEnforcer() {
        this(ToolPolicyCatalog.load(ToolPolicyCatalog.CANDIDATE_RECOMMENDATION_V1));
    }

    private CandidateRecommendationPolicyEnforcer(ToolPolicy policy) {
        this.policy = policy;
    }

    public static CandidateRecommendationPolicyEnforcer loadDefault() {
        return new CandidateRecommendationPolicyEnforcer(
                ToolPolicyCatalog.load(ToolPolicyCatalog.CANDIDATE_RECOMMENDATION_V1));
    }

    public String policyId() {
        return policy.policyId();
    }

    public String gatedTool() {
        return policy.gatedTool();
    }

    public boolean isRecommendationAllowed(List<String> candidateInstanceTypes, ToolEvidenceLedger ledger) {
        return missingEvidence(candidateInstanceTypes, ToolEvidenceLedger.RecommendationWorkload.ANY, ledger).isEmpty();
    }

    public List<String> missingEvidence(List<String> candidateInstanceTypes, ToolEvidenceLedger ledger) {
        return missingEvidence(candidateInstanceTypes, ToolEvidenceLedger.RecommendationWorkload.ANY, ledger);
    }

    private List<String> missingEvidence(
            List<String> candidateInstanceTypes,
            ToolEvidenceLedger.RecommendationWorkload workload,
            ToolEvidenceLedger ledger) {
        List<String> missing = new ArrayList<>();
        for (String candidate : candidateInstanceTypes) {
            for (ToolPolicyEvidenceRequirement requirement : policy.requiredEvidence()) {
                if (!ledger.hasEvidence(requirement.tool(), candidate, workload)) {
                    missing.add(candidate + ":" + requirement.evidence());
                }
            }
        }
        return List.copyOf(missing);
    }

    public boolean isRecommendationAllowed(Map<String, ?> recommendArguments, ToolEvidenceLedger ledger) {
        return missingEvidence(recommendArguments, ledger).isEmpty();
    }

    public List<String> missingEvidence(Map<String, ?> recommendArguments, ToolEvidenceLedger ledger) {
        ToolEvidenceLedger.RecommendationWorkload workload = recommendationWorkload(recommendArguments);
        return missingEvidence(workload.candidateInstanceTypes(), workload, ledger);
    }

    public boolean isEvidenceTool(String toolName) {
        return policy.requiredEvidence().stream().anyMatch(requirement -> requirement.tool().equals(toolName));
    }

    private ToolEvidenceLedger.RecommendationWorkload recommendationWorkload(Map<String, ?> recommendArguments) {
        return new ToolEvidenceLedger.RecommendationWorkload(
                candidateInstances(recommendArguments),
                intArgument(recommendArguments, "modelBillionParameters"),
                stringArgument(recommendArguments, "precision"),
                stringArgument(recommendArguments, "mode"));
    }

    private List<String> candidateInstances(Map<String, ?> recommendArguments) {
        Object candidates = recommendArguments.get(policy.gatedCandidateArgument());
        if (candidates instanceof List<?> values && values.stream().allMatch(String.class::isInstance)) {
            return values.stream()
                    .map(String.class::cast)
                    .toList();
        }
        throw new IllegalArgumentException("Missing candidate list argument: " + policy.gatedCandidateArgument());
    }

    private static int intArgument(Map<String, ?> arguments, String name) {
        Object value = arguments.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            return Integer.parseInt(string);
        }
        throw new IllegalArgumentException("Missing integer argument: " + name);
    }

    private static String stringArgument(Map<String, ?> arguments, String name) {
        Object value = arguments.get(name);
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        throw new IllegalArgumentException("Missing string argument: " + name);
    }
}
