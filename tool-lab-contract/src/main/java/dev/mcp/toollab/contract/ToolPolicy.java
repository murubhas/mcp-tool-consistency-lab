package dev.mcp.toollab.contract;

import java.util.List;

public record ToolPolicy(
        String policyId,
        String description,
        String gatedTool,
        String candidateArgument,
        String gatedCandidateArgument,
        List<ToolPolicyEvidenceRequirement> requiredEvidence) {
    public ToolPolicy {
        requiredEvidence = List.copyOf(requiredEvidence);
    }
}
