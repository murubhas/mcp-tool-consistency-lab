package dev.mcp.toollab.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToolPolicyCatalogTest {
    @Test
    void loadsCandidateRecommendationPolicy() {
        ToolPolicy policy = ToolPolicyCatalog.load(ToolPolicyCatalog.CANDIDATE_RECOMMENDATION_V1);

        assertEquals("candidate-recommendation-v1", policy.policyId());
        assertEquals("recommend_instance", policy.gatedTool());
        assertEquals("instanceType", policy.candidateArgument());
        assertEquals("candidateInstanceTypes", policy.gatedCandidateArgument());
        assertEquals(3, policy.requiredEvidence().size());
        assertEquals("get_instance_spec", policy.requiredEvidence().get(0).tool());
        assertEquals("spec", policy.requiredEvidence().get(0).evidence());
        assertEquals("check_model_fit", policy.requiredEvidence().get(1).tool());
        assertEquals("fit", policy.requiredEvidence().get(1).evidence());
        assertEquals("get_instance_price", policy.requiredEvidence().get(2).tool());
        assertEquals("price", policy.requiredEvidence().get(2).evidence());
    }
}
