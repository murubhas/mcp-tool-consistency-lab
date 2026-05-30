package dev.mcp.toollab.client.quarkus.llmguardrail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.mcp.toollab.client.quarkus.policy.CandidateRecommendationPolicyEnforcer;
import dev.mcp.toollab.client.quarkus.policy.ToolEvidenceLedger;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Singleton
public class RecommendationPlanOutputGuardrail implements OutputGuardrail {
    static final String POLICY_BLOCKED = "POLICY_BLOCKED";

    private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final CandidateRecommendationPolicyEnforcer enforcer;
    private final ToolEvidenceLedger ledger;

    @Inject
    public RecommendationPlanOutputGuardrail(
            CandidateRecommendationPolicyEnforcer enforcer,
            ToolEvidenceLedger ledger,
            ObjectMapper objectMapper) {
        this.enforcer = Objects.requireNonNull(enforcer, "enforcer");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        return validate(request.responseFromLLM().aiMessage());
    }

    @Override
    public OutputGuardrailResult validate(AiMessage aiMessage) {
        for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
            if (!enforcer.gatedTool().equals(toolRequest.name())) {
                continue;
            }
            Map<String, Object> arguments = arguments(toolRequest);
            List<String> missing = enforcer.missingEvidence(arguments, ledger);
            if (!missing.isEmpty()) {
                return failure(POLICY_BLOCKED
                        + ": model proposed recommendation before required evidence: "
                        + String.join(", ", missing));
            }
        }
        for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
            if (enforcer.isEvidenceTool(toolRequest.name())) {
                ledger.recordSuccessfulToolResult(toolRequest.name(), arguments(toolRequest));
            }
        }
        return success();
    }

    private Map<String, Object> arguments(ToolExecutionRequest toolRequest) {
        try {
            return objectMapper.readValue(toolRequest.arguments(), ARGUMENTS_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid tool-call arguments for " + toolRequest.name(), e);
        }
    }
}
