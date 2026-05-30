package dev.mcp.toollab.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mcp.toollab.contract.ToolPolicyEvidenceRequirement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class McpToolEvidenceLedger {
    private final RecommendationPolicyConfig policyConfig;
    private final Map<String, Set<EvidenceKey>> evidenceByState = new ConcurrentHashMap<>();

    @Inject
    McpToolEvidenceLedger(RecommendationPolicyConfig policyConfig) {
        this.policyConfig = policyConfig;
    }

    String gatedTool() {
        return policyConfig.gatedTool();
    }

    String gatedCandidateArgument() {
        return policyConfig.gatedCandidateArgument();
    }

    boolean isEvidenceTool(String toolName) {
        return policyConfig.evidenceByTool().containsKey(toolName);
    }

    void recordEvidence(String stateId, String toolName, JsonNode structuredContent) {
        evidenceRequirement(toolName).ifPresent(requirement -> evidenceByState
                .computeIfAbsent(stateId, ignored -> ConcurrentHashMap.newKeySet())
                .add(new EvidenceKey(
                        structuredContent.path("instanceType").asText(),
                        requirement.evidence(),
                        fitScope(requirement, structuredContent))));
    }

    List<String> missingEvidence(String stateId, RecommendationScope recommendation) {
        Set<EvidenceKey> evidence = evidenceByState.getOrDefault(stateId, Set.of());
        List<String> missing = new ArrayList<>();
        for (String candidate : recommendation.candidateInstanceTypes()) {
            for (ToolPolicyEvidenceRequirement requirement : policyConfig.policy().requiredEvidence()) {
                if (!evidence.contains(new EvidenceKey(candidate, requirement.evidence(), fitScope(requirement, recommendation)))) {
                    missing.add(candidate + ":" + requirement.evidence());
                }
            }
        }
        return List.copyOf(missing);
    }

    private Optional<ToolPolicyEvidenceRequirement> evidenceRequirement(String toolName) {
        return Optional.ofNullable(policyConfig.evidenceByTool().get(toolName));
    }

    private FitScope fitScope(ToolPolicyEvidenceRequirement requirement, JsonNode structuredContent) {
        if (!"fit".equals(requirement.evidence())) {
            return FitScope.ANY;
        }
        return new FitScope(
                structuredContent.path("modelBillionParameters").asInt(),
                structuredContent.path("precision").asText(),
                structuredContent.path("mode").asText());
    }

    private FitScope fitScope(ToolPolicyEvidenceRequirement requirement, RecommendationScope recommendation) {
        return "fit".equals(requirement.evidence()) ? recommendation.fitScope() : FitScope.ANY;
    }

    record RecommendationScope(List<String> candidateInstanceTypes, int modelBillionParameters, String precision, String mode) {
        RecommendationScope {
            candidateInstanceTypes = List.copyOf(candidateInstanceTypes);
        }

        FitScope fitScope() {
            return new FitScope(modelBillionParameters, precision, mode);
        }
    }

    private record EvidenceKey(String instanceType, String evidence, FitScope fitScope) {
    }

    private record FitScope(int modelBillionParameters, String precision, String mode) {
        static final FitScope ANY = new FitScope(-1, "*", "*");
    }
}
