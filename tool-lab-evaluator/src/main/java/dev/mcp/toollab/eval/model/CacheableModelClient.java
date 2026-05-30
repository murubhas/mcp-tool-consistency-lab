package dev.mcp.toollab.eval.model;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mcp.toollab.eval.EvalTask;

import java.util.List;

public interface CacheableModelClient extends ModelClient {
    JsonNode providerRequest(EvalTask task, List<ModelOutput> priorOutputs);

    String rawProviderResponse(EvalTask task, List<ModelOutput> priorOutputs);

    ModelOutput decodeCachedResponse(String rawResponse);

    @Override
    default ModelOutput next(EvalTask task, List<ModelOutput> priorOutputs) {
        return decodeCachedResponse(rawProviderResponse(task, priorOutputs));
    }
}
