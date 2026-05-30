package dev.mcp.toollab.client.quarkus.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CandidateRecommendationPolicyEnforcerTest {
    private static final List<String> CANDIDATES = List.of("p5.48xlarge", "p5e.48xlarge");

    @Test
    void blocksRecommendationWhenAnyCandidateEvidenceIsMissing() {
        CandidateRecommendationPolicyEnforcer enforcer = CandidateRecommendationPolicyEnforcer.loadDefault();
        ToolEvidenceLedger ledger = new ToolEvidenceLedger();
        recordAllEvidence(ledger, "p5.48xlarge");
        ledger.recordSuccessfulToolResult("get_instance_spec", Map.of("instanceType", "p5e.48xlarge"));
        ledger.recordSuccessfulToolResult("check_model_fit", fitArgs("p5e.48xlarge"));

        assertFalse(enforcer.isRecommendationAllowed(CANDIDATES, ledger));
        assertEquals(List.of("p5e.48xlarge:price"), enforcer.missingEvidence(CANDIDATES, ledger));
    }

    @Test
    void allowsRecommendationAfterSpecFitAndPriceEvidenceExistsForEveryCandidate() {
        CandidateRecommendationPolicyEnforcer enforcer = CandidateRecommendationPolicyEnforcer.loadDefault();
        ToolEvidenceLedger ledger = new ToolEvidenceLedger();
        CANDIDATES.forEach(candidate -> recordAllEvidence(ledger, candidate));

        assertEquals("candidate-recommendation-v1", enforcer.policyId());
        assertEquals("recommend_instance", enforcer.gatedTool());
        assertTrue(enforcer.isRecommendationAllowed(recommendArgs(), ledger));
    }

    @Test
    void mismatchedFitEvidenceDoesNotAuthorizeRecommendationWorkload() {
        CandidateRecommendationPolicyEnforcer enforcer = CandidateRecommendationPolicyEnforcer.loadDefault();
        ToolEvidenceLedger ledger = new ToolEvidenceLedger();
        for (String candidate : CANDIDATES) {
            ledger.recordSuccessfulToolResult("get_instance_spec", Map.of("instanceType", candidate));
            ledger.recordSuccessfulToolResult("check_model_fit", Map.of(
                    "instanceType", candidate,
                    "modelBillionParameters", 34,
                    "precision", "fp8",
                    "mode", "inference"));
            ledger.recordSuccessfulToolResult("get_instance_price", Map.of("instanceType", candidate));
        }

        assertFalse(enforcer.isRecommendationAllowed(recommendArgs(), ledger));
        assertEquals(List.of("p5.48xlarge:fit", "p5e.48xlarge:fit"), enforcer.missingEvidence(recommendArgs(), ledger));
    }

    private static void recordAllEvidence(ToolEvidenceLedger ledger, String instanceType) {
        ledger.recordSuccessfulToolResult("get_instance_spec", Map.of("instanceType", instanceType));
        ledger.recordSuccessfulToolResult("check_model_fit", fitArgs(instanceType));
        ledger.recordSuccessfulToolResult("get_instance_price", Map.of("instanceType", instanceType));
    }

    private static Map<String, Object> fitArgs(String instanceType) {
        return Map.of(
                "instanceType", instanceType,
                "modelBillionParameters", 70,
                "precision", "bf16",
                "mode", "fine_tuning");
    }

    private static Map<String, Object> recommendArgs() {
        return Map.of(
                "candidateInstanceTypes", CANDIDATES,
                "modelBillionParameters", 70,
                "precision", "bf16",
                "mode", "fine_tuning",
                "optimizeFor", "cheapest");
    }
}
