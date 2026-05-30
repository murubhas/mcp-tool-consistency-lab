package dev.mcp.toollab.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mcp.toollab.contract.ToolLabPromptCatalog;
import dev.mcp.toollab.contract.ToolLabPrompt;
import dev.mcp.toollab.eval.harness.McpHttpToolExecutionClient;
import dev.mcp.toollab.eval.harness.ToolCallingHarness;
import dev.mcp.toollab.eval.harness.ToolExecutionClient;
import dev.mcp.toollab.eval.model.AwsSdkBedrockRuntimeInvoker;
import dev.mcp.toollab.eval.model.CachedModelClient;
import dev.mcp.toollab.eval.model.CacheableModelClient;
import dev.mcp.toollab.eval.model.DecodingConfig;
import dev.mcp.toollab.eval.model.MockModelClient;
import dev.mcp.toollab.eval.model.ModelClient;
import dev.mcp.toollab.eval.model.ProviderResponseMemoizer;
import dev.mcp.toollab.eval.model.QwenOpenAiCompatibleClient;
import dev.mcp.toollab.eval.model.SonnetBedrockClient;
import dev.mcp.toollab.eval.reporting.ConsoleReporter;
import dev.mcp.toollab.eval.reporting.RunManifestWriter;
import dev.mcp.toollab.eval.reporting.SummaryWriter;
import dev.mcp.toollab.eval.schema.ToolSchemaRegistry;
import dev.mcp.toollab.eval.schema.ToolSchemaValidator;
import dev.mcp.toollab.eval.trace.TraceRecord;
import dev.mcp.toollab.eval.trace.TraceRecorder;
import dev.mcp.toollab.eval.validation.AcceptedTraceSet;
import dev.mcp.toollab.eval.validation.TraceValidator;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@QuarkusMain
public class ConsistencyEvalCommand implements QuarkusApplication {
    @Inject
    ObjectMapper mapper;

    @Inject
    Instance<ProviderResponseMemoizer> memoizer;

    @Inject
    Instance<BedrockRuntimeClient> bedrockRuntimeClient;

    boolean dryRun = true;

    Path resultsRoot = Path.of("results");
    Path cacheRoot = Path.of("results", "cache");
    String model = "mock";
    String endpoint;
    String modelId;
    String modelRevision;
    String servedModelName;
    String region;
    String promptVariant;
    Boolean qwenEnableThinking;
    Boolean qwenPreserveThinking;
    boolean useCache = true;
    boolean refreshCache = false;
    Set<String> taskIds = new LinkedHashSet<>();
    String category;
    Integer limit;
    int repeat = 1;
    boolean failFast = false;
    int maxOutputTokens = 1024;
    String toolExecution = McpHttpToolExecutionClient.MODE;
    String mcpEndpoint;

    static void main(String[] args) {
        Quarkus.run(ConsistencyEvalCommand.class, args);
    }

    @Override
    public int run(String... args) {
        parseArgs(args);

        String runId = runIdPrefix() + "-" + Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
                .replace(":", "")
                .replace("-", "");
        ToolSchemaRegistry registry = ToolSchemaRegistry.loadDefault(mapper);
        AcceptedTraceSet acceptedTraceSet = AcceptedTraceSet.loadDefault(mapper);
        TraceValidator traceValidator = new TraceValidator(acceptedTraceSet, registry, mapper);
        ModelClient client = buildClient(registry);
        ToolExecutionClient toolExecutionClient = buildToolExecutionClient(registry);
        ToolCallingHarness harness = new ToolCallingHarness(registry, traceValidator, toolExecutionClient, mapper);
        EvalTaskLoader.LoadedTasks loadedTasks = new EvalTaskLoader(mapper).loadMilestoneTasks();
        List<EvalTask> selectedTasks = selectTasks(loadedTasks.tasks());

        List<TraceRecord> records = new ArrayList<>();
        Path runDir = resultsRoot.resolve(runId);
        try {
            for (int repeatIndex = 1; repeatIndex <= repeat; repeatIndex++) {
                for (EvalTask task : selectedTasks) {
                    TraceRecord record = harness.run(runId, task, client);
                    record.json().put("category", task.category());
                    record.json().put("repeatIndex", repeatIndex);
                    records.add(record);
                    System.out.println(ConsoleReporter.formatRecord(record));
                    if (failFast && !record.score("overallPass")) {
                        writeResults(runId, registry, loadedTasks, acceptedTraceSet, client, records, selectedTasks.size());
                        System.out.println(ConsoleReporter.formatSummary(records, runDir));
                        return 1;
                    }
                }
            }

            writeResults(runId, registry, loadedTasks, acceptedTraceSet, client, records, selectedTasks.size());
            System.out.println(ConsoleReporter.formatSummary(records, runDir));
            return 0;
        } finally {
            toolExecutionClient.close();
        }
    }

    private void writeResults(
            String runId,
            ToolSchemaRegistry registry,
            EvalTaskLoader.LoadedTasks loadedTasks,
            AcceptedTraceSet acceptedTraceSet,
            ModelClient client,
            List<TraceRecord> records,
            int selectedTaskCount) {
        Path runDir = resultsRoot.resolve(runId);
        new TraceRecorder(runDir.resolve("traces.jsonl"), mapper).write(records);
        RunManifestWriter manifestWriter = new RunManifestWriter(mapper);
        ObjectNode manifest = manifestWriter.build(
                runId,
                registry,
                loadedTasks.hash(),
                acceptedTraceSet,
                client,
                records.stream().allMatch(record -> record.json().path("outputsFromCache").asBoolean(false)),
                records.isEmpty()
                        ? toolExecution
                        : records.getFirst().json().path("toolExecutionMode").asText(toolExecution));
        manifestWriter.validateAndWrite(manifest, runDir.resolve("run-manifest.json"));
        new SummaryWriter().write(runId, records, runDir.resolve("summary.md"), selectedTaskCount, repeat);
    }

    protected ToolExecutionClient buildToolExecutionClient(ToolSchemaRegistry registry) {
        Config config = ConfigProvider.getConfig();
        String effectiveMode = firstNonBlank(toolExecution, optional(config, "tool.lab.tool-execution"))
                .orElse(McpHttpToolExecutionClient.MODE);
        ToolSchemaValidator validator = new ToolSchemaValidator(registry);
        if (McpHttpToolExecutionClient.MODE.equals(effectiveMode)) {
            return new McpHttpToolExecutionClient(
                    URI.create(firstNonBlank(mcpEndpoint, optional(config, "tool.lab.mcp.endpoint"))
                            .orElse("http://localhost:8081/mcp")),
                    validator,
                    mapper);
        }
        throw new IllegalArgumentException(
                "--tool-execution must be mcp-http in the packaged evaluator, got: " + effectiveMode
                        + ". Direct-domain execution has been removed from tool-lab-evaluator.");
    }

    protected ModelClient buildClient(ToolSchemaRegistry registry) {
        Config config = ConfigProvider.getConfig();
        DecodingConfig decoding = DecodingConfig.deterministic(maxOutputTokens);
        ToolLabPrompt prompt = resolvePrompt(config);
        if ("mock".equals(model)) {
            if (!dryRun) {
                throw new IllegalArgumentException("--model mock must run with --dry-run=true");
            }
            return new MockModelClient(prompt, mapper);
        }
        if (dryRun) {
            throw new IllegalArgumentException("Live model '" + model + "' requires --dry-run=false");
        }

        CacheableModelClient liveClient = switch (model) {
            case "qwen" -> buildQwenClient(config, registry, decoding, prompt);
            case "sonnet" -> buildSonnetClient(config, registry, decoding, prompt);
            default -> throw new IllegalArgumentException("Unknown model: " + model);
        };
        if (!useCache) {
            return liveClient;
        }
        return CachedModelClient.builder()
                .delegate(liveClient)
                .registry(registry)
                .cacheRoot(cacheRoot)
                .memoizer(memoizer.get())
                .mapper(mapper)
                .refreshCache(refreshCache)
                .build();
    }

    private List<EvalTask> selectTasks(List<EvalTask> tasks) {
        if (!dryRun && limit == null && taskIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Live runs require --limit or --task-id to avoid accidental all-task execution.");
        }
        List<EvalTask> selected = tasks.stream()
                .filter(task -> taskIds.isEmpty() || taskIds.contains(task.taskId()))
                .filter(task -> category == null || category.equals(task.category()))
                .toList();
        if (limit != null && selected.size() > limit) {
            selected = selected.subList(0, limit);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Task filters selected zero tasks");
        }
        return selected;
    }

    private CacheableModelClient buildQwenClient(
            Config config,
            ToolSchemaRegistry registry,
            DecodingConfig decoding,
            ToolLabPrompt prompt) {
        String effectiveEndpoint = firstNonBlank(endpoint, optional(config, "quarkus.rest-client.qwen-openai.url"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Qwen live runs require --endpoint or quarkus.rest-client.qwen-openai.url"));
        String effectiveModelId = firstNonBlank(modelId, optional(config, "tool.lab.qwen.model-id"))
                .orElse("Qwen/Qwen3.6-27B");
        String effectiveRevision = firstNonBlank(modelRevision, optional(config, "tool.lab.qwen.model-revision"))
                .orElse("not-configured");
        String effectiveServedModelName = firstNonBlank(servedModelName, optional(config, "tool.lab.qwen.served-model-name"))
                .orElse(effectiveModelId);
        String apiKey = firstNonBlank(null, optional(config, "tool.lab.qwen.api-key")).orElse(null);
        Boolean effectiveEnableThinking = qwenEnableThinking != null
                ? qwenEnableThinking
                : optionalBoolean(config, "tool.lab.qwen.enable-thinking").orElse(null);
        Boolean effectivePreserveThinking = qwenPreserveThinking != null
                ? qwenPreserveThinking
                : optionalBoolean(config, "tool.lab.qwen.preserve-thinking").orElse(null);
        return QwenOpenAiCompatibleClient.builder()
                .endpoint(URI.create(effectiveEndpoint))
                .apiKey(apiKey)
                .modelId(effectiveModelId)
                .servedModelName(effectiveServedModelName)
                .modelRevision(effectiveRevision)
                .registry(registry)
                .decodingConfig(decoding)
                .mapper(mapper)
                .prompt(prompt)
                .enableThinking(effectiveEnableThinking)
                .preserveThinking(effectivePreserveThinking)
                .build();
    }

    private CacheableModelClient buildSonnetClient(
            Config config,
            ToolSchemaRegistry registry,
            DecodingConfig decoding,
            ToolLabPrompt prompt) {
        if (region != null && !region.isBlank()) {
            throw new IllegalArgumentException(
                    "--region is not supported for --model sonnet. Configure Bedrock region with "
                            + "quarkus.bedrockruntime.aws.region or QUARKUS_BEDROCKRUNTIME_AWS_REGION so the "
                            + "Quarkus-managed BedrockRuntimeClient and run manifest use the same value.");
        }
        if (endpoint != null && !endpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "--endpoint is not supported for --model sonnet. Configure Bedrock endpoint override with "
                            + "quarkus.bedrockruntime.endpoint-override or "
                            + "QUARKUS_BEDROCKRUNTIME_ENDPOINT_OVERRIDE so the Quarkus-managed BedrockRuntimeClient "
                            + "and run manifest use the same value.");
        }
        String effectiveModelId = firstNonBlank(modelId, optional(config, "tool.lab.sonnet.model-id"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sonnet live runs require --model-id or tool.lab.sonnet.model-id/TOOL_LAB_SONNET_MODEL_ID"));
        String effectiveRevision = firstNonBlank(modelRevision, optional(config, "tool.lab.sonnet.model-revision"))
                .orElse("not-configured");
        String effectiveRegion = optional(config, "quarkus.bedrockruntime.aws.region")
                .orElse("quarkus-config/default-provider-chain");
        String effectiveEndpoint = optional(config, "quarkus.bedrockruntime.endpoint-override")
                .orElse("quarkus-config/default-provider-chain");
        return new SonnetBedrockClient(
                effectiveModelId,
                effectiveRevision,
                effectiveRegion,
                effectiveEndpoint,
                registry,
                decoding,
                new AwsSdkBedrockRuntimeInvoker(bedrockRuntimeClient.get(), mapper),
                mapper,
                prompt);
    }

    private String runIdPrefix() {
        if (dryRun) {
            return "dry-run";
        }
        return model + "-live";
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("eval")) {
                continue;
            }
            if (arg.equals("--dry-run")) {
                dryRun = true;
                continue;
            }
            if (arg.startsWith("--dry-run=")) {
                dryRun = Boolean.parseBoolean(arg.substring("--dry-run=".length()));
                continue;
            }
            if (arg.equals("--results-root")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--results-root requires a value");
                }
                resultsRoot = Path.of(args[++i]);
                continue;
            }
            if (arg.startsWith("--results-root=")) {
                resultsRoot = Path.of(arg.substring("--results-root=".length()));
                continue;
            }
            if (arg.equals("--cache-root")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--cache-root requires a value");
                }
                cacheRoot = Path.of(args[++i]);
                continue;
            }
            if (arg.startsWith("--cache-root=")) {
                cacheRoot = Path.of(arg.substring("--cache-root=".length()));
                continue;
            }
            if (arg.equals("--model")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--model requires a value");
                }
                model = args[++i];
                continue;
            }
            if (arg.startsWith("--model=")) {
                model = arg.substring("--model=".length());
                continue;
            }
            if (arg.equals("--endpoint")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--endpoint requires a value");
                }
                endpoint = args[++i];
                continue;
            }
            if (arg.startsWith("--endpoint=")) {
                endpoint = arg.substring("--endpoint=".length());
                continue;
            }
            if (arg.equals("--model-id")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--model-id requires a value");
                }
                modelId = args[++i];
                continue;
            }
            if (arg.startsWith("--model-id=")) {
                modelId = arg.substring("--model-id=".length());
                continue;
            }
            if (arg.equals("--model-revision")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--model-revision requires a value");
                }
                modelRevision = args[++i];
                continue;
            }
            if (arg.startsWith("--model-revision=")) {
                modelRevision = arg.substring("--model-revision=".length());
                continue;
            }
            if (arg.equals("--served-model-name")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--served-model-name requires a value");
                }
                servedModelName = args[++i];
                continue;
            }
            if (arg.startsWith("--served-model-name=")) {
                servedModelName = arg.substring("--served-model-name=".length());
                continue;
            }
            if (arg.equals("--prompt-variant")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--prompt-variant requires a value");
                }
                promptVariant = args[++i];
                continue;
            }
            if (arg.startsWith("--prompt-variant=")) {
                promptVariant = arg.substring("--prompt-variant=".length());
                continue;
            }
            if (arg.equals("--qwen-enable-thinking")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--qwen-enable-thinking requires true or false");
                }
                qwenEnableThinking = booleanValue("--qwen-enable-thinking", args[++i]);
                continue;
            }
            if (arg.startsWith("--qwen-enable-thinking=")) {
                qwenEnableThinking = booleanValue(
                        "--qwen-enable-thinking",
                        arg.substring("--qwen-enable-thinking=".length()));
                continue;
            }
            if (arg.equals("--qwen-preserve-thinking")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--qwen-preserve-thinking requires true or false");
                }
                qwenPreserveThinking = booleanValue("--qwen-preserve-thinking", args[++i]);
                continue;
            }
            if (arg.startsWith("--qwen-preserve-thinking=")) {
                qwenPreserveThinking = booleanValue(
                        "--qwen-preserve-thinking",
                        arg.substring("--qwen-preserve-thinking=".length()));
                continue;
            }
            if (arg.equals("--region")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--region requires a value");
                }
                region = args[++i];
                continue;
            }
            if (arg.startsWith("--region=")) {
                region = arg.substring("--region=".length());
                continue;
            }
            if (arg.equals("--use-cache")) {
                useCache = true;
                continue;
            }
            if (arg.startsWith("--use-cache=")) {
                useCache = Boolean.parseBoolean(arg.substring("--use-cache=".length()));
                continue;
            }
            if (arg.equals("--refresh-cache")) {
                refreshCache = true;
                continue;
            }
            if (arg.equals("--task-id")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--task-id requires a value");
                }
                addTaskIds(args[++i]);
                continue;
            }
            if (arg.startsWith("--task-id=")) {
                addTaskIds(arg.substring("--task-id=".length()));
                continue;
            }
            if (arg.equals("--category")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--category requires a value");
                }
                category = args[++i];
                continue;
            }
            if (arg.startsWith("--category=")) {
                category = arg.substring("--category=".length());
                continue;
            }
            if (arg.equals("--limit")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--limit requires a value");
                }
                limit = positiveInt("--limit", args[++i]);
                continue;
            }
            if (arg.startsWith("--limit=")) {
                limit = positiveInt("--limit", arg.substring("--limit=".length()));
                continue;
            }
            if (arg.equals("--repeat")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--repeat requires a value");
                }
                repeat = positiveInt("--repeat", args[++i]);
                continue;
            }
            if (arg.startsWith("--repeat=")) {
                repeat = positiveInt("--repeat", arg.substring("--repeat=".length()));
                continue;
            }
            if (arg.equals("--fail-fast")) {
                failFast = true;
                continue;
            }
            if (arg.startsWith("--fail-fast=")) {
                failFast = Boolean.parseBoolean(arg.substring("--fail-fast=".length()));
                continue;
            }
            if (arg.equals("--max-output-tokens")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--max-output-tokens requires a value");
                }
                maxOutputTokens = Integer.parseInt(args[++i]);
                continue;
            }
            if (arg.startsWith("--max-output-tokens=")) {
                maxOutputTokens = Integer.parseInt(arg.substring("--max-output-tokens=".length()));
                continue;
            }
            if (arg.equals("--tool-execution")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--tool-execution requires mcp-http");
                }
                toolExecution = args[++i];
                continue;
            }
            if (arg.startsWith("--tool-execution=")) {
                toolExecution = arg.substring("--tool-execution=".length());
                continue;
            }
            if (arg.equals("--mcp-endpoint")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--mcp-endpoint requires a value");
                }
                mcpEndpoint = args[++i];
                continue;
            }
            if (arg.startsWith("--mcp-endpoint=")) {
                mcpEndpoint = arg.substring("--mcp-endpoint=".length());
                continue;
            }
            throw new IllegalArgumentException("Unknown argument: " + arg);
        }
    }

    private void addTaskIds(String value) {
        for (String item : value.split(",")) {
            String taskId = item.trim();
            if (!taskId.isBlank()) {
                taskIds.add(taskId);
            }
        }
    }

    private static int positiveInt(String flag, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException(flag + " must be >= 1");
        }
        return parsed;
    }

    private static boolean booleanValue(String flag, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(flag + " must be true or false");
    }

    private static Optional<String> optional(Config config, String property) {
        return config.getOptionalValue(property, String.class).filter(value -> !value.isBlank());
    }

    private static Optional<Boolean> optionalBoolean(Config config, String property) {
        return config.getOptionalValue(property, Boolean.class);
    }

    private ToolLabPrompt resolvePrompt(Config config) {
        String effectiveVariant = firstNonBlank(promptVariant, optional(config, "tool.lab.prompt.variant"))
                .orElse(ToolLabPromptCatalog.DEFAULT_VARIANT);
        return new ToolLabPromptCatalog().resolve(effectiveVariant);
    }

    private static Optional<String> firstNonBlank(String first, Optional<String> second) {
        if (first != null && !first.isBlank()) {
            return Optional.of(first);
        }
        return second;
    }
}
