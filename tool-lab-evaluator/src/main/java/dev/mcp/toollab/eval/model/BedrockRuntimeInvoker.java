package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface BedrockRuntimeInvoker {
    String invoke(String modelId, ObjectNode request);
}
