package dev.mcp.toollab.server;

import dev.mcp.toollab.contract.ToolPolicy;
import dev.mcp.toollab.contract.ToolPolicyEvidenceRequirement;

import java.util.Map;

public record RecommendationPolicyConfig(
        ToolPolicy policy,
        Map<String, ToolPolicyEvidenceRequirement> evidenceByTool) {

    public RecommendationPolicyConfig {
        evidenceByTool = Map.copyOf(evidenceByTool);
    }

    String gatedTool() {
        return policy.gatedTool();
    }

    String gatedCandidateArgument() {
        return policy.gatedCandidateArgument();
    }
}
