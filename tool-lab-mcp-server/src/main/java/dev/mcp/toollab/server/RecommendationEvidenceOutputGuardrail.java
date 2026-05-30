package dev.mcp.toollab.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import io.quarkiverse.mcp.server.ToolOutputGuardrail.ToolOutputContext;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RecommendationEvidenceOutputGuardrail implements ToolOutputGuardrail {
    private final ObjectMapper mapper;
    private final McpToolEvidenceLedger ledger;

    @Inject
    public RecommendationEvidenceOutputGuardrail(ObjectMapper mapper, McpToolEvidenceLedger ledger) {
        this.mapper = mapper;
        this.ledger = ledger;
    }

    @Override
    public void apply(ToolOutputContext context) {
        String toolName = context.getTool().name();
        if (!ledger.isEvidenceTool(toolName)) {
            return;
        }
        ToolResponse response = context.getResponse();
        if (response == null || response.isError() || response.structuredContent() == null) {
            return;
        }
        JsonNode structured = mapper.valueToTree(response.structuredContent());
        String instanceType = structured.path("instanceType").asText(null);
        if (instanceType == null || instanceType.isBlank()) {
            return;
        }
        String stateId = McpStateIds.stateId(context.getMeta(), context.getConnection());
        ledger.recordEvidence(stateId, toolName, structured);
    }
}
