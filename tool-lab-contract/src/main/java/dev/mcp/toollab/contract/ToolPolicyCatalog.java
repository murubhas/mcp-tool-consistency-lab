package dev.mcp.toollab.contract;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public final class ToolPolicyCatalog {
    public static final String CANDIDATE_RECOMMENDATION_V1 = "candidate-recommendation-v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> POLICY_RESOURCES = Map.of(
            CANDIDATE_RECOMMENDATION_V1, "policies/candidate-recommendation-policy-v1.json");

    private ToolPolicyCatalog() {
    }

    public static ToolPolicy load(String policyId) {
        String resource = POLICY_RESOURCES.get(policyId);
        if (resource == null) {
            throw new IllegalArgumentException("Unknown tool policy: " + policyId);
        }
        try (InputStream stream = ToolPolicyCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing tool policy resource: " + resource);
            }
            ToolPolicy policy = MAPPER.readValue(stream, ToolPolicy.class);
            if (!policyId.equals(policy.policyId())) {
                throw new IllegalStateException("Tool policy ID mismatch for " + resource + ": " + policy.policyId());
            }
            return policy;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load tool policy: " + policyId, e);
        }
    }
}
