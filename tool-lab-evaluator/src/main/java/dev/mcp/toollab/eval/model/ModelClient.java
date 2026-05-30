package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mcp.toollab.eval.EvalTask;

import java.util.List;

public interface ModelClient {
    String modelId();

    String modelRevision();

    String providerSchemaAdapter();

    JsonNode modelConfig();

    JsonNode decodingConfig();

    ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs);
}
