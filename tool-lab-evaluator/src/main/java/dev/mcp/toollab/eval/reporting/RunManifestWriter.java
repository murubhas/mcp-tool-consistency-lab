package dev.mcp.toollab.eval.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.CanonicalJson;
import dev.mcp.toollab.contract.ToolLabPrompt;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import dev.mcp.toollab.eval.model.ModelClient;
import dev.mcp.toollab.eval.schema.JsonResourceSchemaValidator;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.eval.schema.ValidationResult;
import dev.mcp.toollab.eval.validation.AcceptedTraceSet;
import dev.mcp.toollab.eval.validation.TraceValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public final class RunManifestWriter {
    private final ObjectMapper mapper;
    private final JsonResourceSchemaValidator validator;

    public RunManifestWriter(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.validator = new JsonResourceSchemaValidator(mapper);
    }

    public ObjectNode build(
            String runId,
            ToolSchemaRegistry registry,
            String evalDatasetHash,
            AcceptedTraceSet acceptedTraceSet,
            ModelClient client,
            boolean outputsFromCache) {
        return build(runId, registry, evalDatasetHash, acceptedTraceSet, client, outputsFromCache, "mcp-http");
    }

    public ObjectNode build(
            String runId,
            ToolSchemaRegistry registry,
            String evalDatasetHash,
            AcceptedTraceSet acceptedTraceSet,
            ModelClient client,
            boolean outputsFromCache,
            String toolExecutionMode) {
        ObjectNode root = mapper.createObjectNode();
        root.put("runId", runId);
        root.put("createdAt", Instant.now().toString());
        root.put("gitCommitSha", "unknown-local");
        root.put("evaluatorVersion", "0.1.0-SNAPSHOT");
        root.put("toolSchemaVersion", registry.version());
        root.put("toolSchemasHash", registry.hash());
        root.put("evalDatasetVersion", "milestone-1");
        root.put("evalDatasetHash", evalDatasetHash);
        root.put("trainingDatasetVersion", "not-applicable");
        root.put("trainingDatasetHash", "not-applicable");
        root.put("acceptedTraceSetHash", acceptedTraceSet.hash());
        var modelConfig = client.modelConfig();
        ToolLabPrompt defaultPrompt = new ToolLabPromptCatalog().resolve(ToolLabPromptCatalog.DEFAULT_VARIANT);
        ObjectNode model = root.putObject("model");
        model.put("modelId", client.modelId());
        model.put("modelRevision", client.modelRevision());
        model.set("config", modelConfig);
        model.put("servingArtifactUri", modelConfig.path("servingArtifactUri").asText("not-configured"));
        ObjectNode prompt = root.putObject("prompt");
        prompt.put("variant", modelConfig.path("promptVariant").asText(defaultPrompt.variant()));
        prompt.put("hash", modelConfig.path("promptHash").asText(defaultPrompt.hash()));
        prompt.put("source", modelConfig.path("promptSource").asText(defaultPrompt.source()));
        root.put("providerSchemaAdapter", client.providerSchemaAdapter());
        root.set("decoding", client.decodingConfig());
        root.put("quarkusVersion", "3.32.3");
        root.put("quarkusLangChain4jVersion", "not-used");
        root.put("langChain4jVersion", "not-used");
        root.put("vllmVersion", modelConfig.path("vllmVersion").asText("not-used"));
        root.put("servingHardwareShape", modelConfig.path("servingHardwareShape").asText("not-configured"));
        root.put("bedrockModelId", modelConfig.path("bedrockModelId").asText("not-used"));
        root.put("bedrockRegion", modelConfig.path("bedrockRegion").asText("not-used"));
        root.put("stateCanonicalizationVersion", CanonicalJson.STATE_CANONICALIZATION_VERSION);
        root.put("traceEquivalenceVersion", TraceValidator.TRACE_EQUIVALENCE_VERSION);
        root.put("toolExecutionMode", toolExecutionMode);
        root.put("randomSeed", 7);
        root.put("outputsFromCache", outputsFromCache);
        return root;
    }

    public void validateAndWrite(ObjectNode manifest, Path outputPath) {
        ValidationResult result = validator.validateResource("/schemas/run-manifest.schema.json", manifest);
        if (!result.valid()) {
            throw new IllegalStateException("Run manifest schema validation failed: " + result.errors());
        }
        try {
            Files.createDirectories(outputPath.getParent());
            Files.writeString(
                    outputPath,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write run manifest " + outputPath, e);
        }
    }
}
