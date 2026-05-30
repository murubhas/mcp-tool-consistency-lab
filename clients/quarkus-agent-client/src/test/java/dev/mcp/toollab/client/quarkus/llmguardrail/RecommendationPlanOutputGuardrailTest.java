package dev.mcp.toollab.client.quarkus.llmguardrail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import dev.mcp.toollab.client.quarkus.policy.CandidateRecommendationPolicyEnforcer;
import dev.mcp.toollab.client.quarkus.policy.ToolEvidenceLedger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationPlanOutputGuardrailTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CANDIDATES = List.of("p5.48xlarge", "p5e.48xlarge");

    @Test
    void llmOutputGuardrailBlocksPrematureRecommendationToolPlan() {
        RecommendationPlanOutputGuardrail guardrail = guardrail(new ToolEvidenceLedger());

        OutputGuardrailResult result = guardrail.validate(AiMessage.from(toolRequest("recommend_instance", recommendArgs())));

        assertFalse(result.isSuccess());
        assertTrue(result.failures().getFirst().message().startsWith(RecommendationPlanOutputGuardrail.POLICY_BLOCKED));
        assertTrue(result.failures().getFirst().message().contains("p5.48xlarge:spec"));
    }

    @Test
    void llmOutputGuardrailAllowsRecommendationAfterEvidenceExists() {
        ToolEvidenceLedger ledger = new ToolEvidenceLedger();
        CANDIDATES.forEach(candidate -> recordAllEvidence(ledger, candidate));
        RecommendationPlanOutputGuardrail guardrail = guardrail(ledger);

        OutputGuardrailResult result = guardrail.validate(AiMessage.from(toolRequest("recommend_instance", recommendArgs())));

        assertTrue(result.isSuccess());
    }

    @Test
    void llmOutputGuardrailRecordsEvidenceToolPlansForLaterRecommendationChecks() {
        ToolEvidenceLedger ledger = new ToolEvidenceLedger();
        RecommendationPlanOutputGuardrail guardrail = guardrail(ledger);
        for (String candidate : CANDIDATES) {
            assertTrue(guardrail.validate(AiMessage.from(toolRequest("get_instance_spec", Map.of("instanceType", candidate)))).isSuccess());
            assertTrue(guardrail.validate(AiMessage.from(toolRequest("check_model_fit", fitArgs(candidate)))).isSuccess());
            assertTrue(guardrail.validate(AiMessage.from(toolRequest("get_instance_price", Map.of("instanceType", candidate)))).isSuccess());
        }

        OutputGuardrailResult result = guardrail.validate(AiMessage.from(toolRequest("recommend_instance", recommendArgs())));

        assertTrue(result.isSuccess());
    }

    @Test
    void llmOutputGuardrailRejectsMismatchedFitEvidence() {
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
        RecommendationPlanOutputGuardrail guardrail = guardrail(ledger);

        OutputGuardrailResult result = guardrail.validate(AiMessage.from(toolRequest("recommend_instance", recommendArgs())));

        assertFalse(result.isSuccess());
        assertTrue(result.failures().getFirst().message().contains("p5.48xlarge:fit"));
        assertTrue(result.failures().getFirst().message().contains("p5e.48xlarge:fit"));
    }

    @Test
    void llmOutputGuardrailIgnoresNonToolFinalAnswer() {
        RecommendationPlanOutputGuardrail guardrail = guardrail(new ToolEvidenceLedger());

        OutputGuardrailResult result = guardrail.validate(AiMessage.from("p5.48xlarge is the cheapest valid option."));

        assertTrue(result.isSuccess());
    }

    @Test
    void demoAssistantShowsQuarkusLlmOutputGuardrailWiring() throws Exception {
        Method chat = GuardedPlanAssistantDemo.class.getMethod("chat", String.class);

        OutputGuardrails outputGuardrails = chat.getAnnotation(OutputGuardrails.class);

        assertNotNull(outputGuardrails);
        assertEquals(RecommendationPlanOutputGuardrail.class, outputGuardrails.value()[0]);
    }

    private static RecommendationPlanOutputGuardrail guardrail(ToolEvidenceLedger ledger) {
        return new RecommendationPlanOutputGuardrail(CandidateRecommendationPolicyEnforcer.loadDefault(), ledger, MAPPER);
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

    private static ToolExecutionRequest toolRequest(String name, Map<String, ?> arguments) {
        try {
            return ToolExecutionRequest.builder()
                    .id("tool-" + name)
                    .name(name)
                    .arguments(MAPPER.writeValueAsString(arguments))
                    .build();
        } catch (JsonProcessingException e) {
            throw new AssertionError("Unable to serialize tool arguments", e);
        }
    }
}
