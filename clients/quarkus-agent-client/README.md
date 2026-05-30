# Quarkus Agent Client

This Quarkus app demonstrates AI agent consumption of the Tool Lab MCP server. It uses `@RegisterAiService(modelName = "tool-lab")`, `@McpToolBox("tool-lab")`, and Qwen through the Quarkus LangChain4j OpenAI-compatible adapter by default. A `bedrock` profile is available when you want the same AI service and MCP tools to run through Bedrock Claude Sonnet.

This client is not used for benchmark scoring; `tool-lab-evaluator` remains the deterministic source of truth.

## Run

Run Maven commands from the lab root, `mcp-tool-consistency-lab`, so the reactor can build `tool-lab-contract` first.

Start the MCP server from the lab root:

```shell
./mvnw -pl tool-lab-mcp-server -am quarkus:dev
```

The client is configured for that local MCP server and a local Qwen/vLLM endpoint by default:

```shell
QWEN_OPENAI_BASE_URL=http://localhost:8000/v1 \
QWEN_MODEL=qwen36-27b-all1000-plus-toollab-no-tool-fp8 \
./mvnw -pl clients/quarkus-agent-client -am quarkus:dev -Dquarkus.args='--scenario all'
```

Set `TOOL_LAB_MCP_URL` if the MCP server is bound somewhere else. Set `QWEN_MODEL` to the model ID returned by `GET /v1/models` on your vLLM endpoint. If your endpoint requires a bearer token, set `QWEN_OPENAI_API_KEY`.
Supported scenarios: `spec`, `price`, `no-tool`, `serial`, `parallel`, `mixed-dag`, and `all`.

The `all` smoke runs six scenarios as independent AI interactions: exact specs, on-demand price, no-tool creative writing, serial fit-then-price, parallel comparison, and candidate comparison with specs, fit, price, then recommendation. Each scenario gets a fresh `@MemoryId` so context does not leak between scenarios.

This client also includes a control-plane policy demo for the mixed-DAG recommendation case. A small evidence ledger plus a LangChain4j `OutputGuardrail` rejects a model-proposed `recommend_instance` plan until every candidate has spec, fit, and price evidence. The MCP server remains the stronger shared enforcement point.

The client is part of the lab Maven reactor because it depends on `tool-lab-contract` for the shared policy artifact.

You can also package and run the command:

```shell
./mvnw -pl clients/quarkus-agent-client -am package
QWEN_OPENAI_BASE_URL=http://localhost:8000/v1 \
QWEN_MODEL=qwen36-27b-all1000-plus-toollab-no-tool-fp8 \
java -jar clients/quarkus-agent-client/target/quarkus-app/quarkus-run.jar --scenario spec
```

The REST endpoint is available at:

```shell
curl 'http://localhost:8080/agent-demo?scenario=price'
```

### Run With Bedrock Sonnet

The Bedrock path uses the same named AI service, MCP tool box, prompts, and guardrails. Only the chat model provider changes from Qwen/OpenAI-compatible to Bedrock.

```shell
AWS_REGION=us-east-2 \
BEDROCK_GROUNDING_MODEL_ID=us.anthropic.claude-sonnet-4-6 \
./mvnw -pl clients/quarkus-agent-client -am quarkus:dev \
  -Dquarkus.profile=bedrock \
  -Dquarkus.args='--scenario all'
```

AWS credentials come from the default AWS credential provider chain. Keep `TOOL_LAB_MCP_URL` pointed at the Tool Lab MCP server; the Bedrock setting is only the chat model backend.

## Configuration

- `quarkus.langchain4j.mcp.tool-lab.transport-type`: `streamable-http`
- `quarkus.langchain4j.mcp.tool-lab.url`: `${TOOL_LAB_MCP_URL:http://localhost:8088/mcp}`
- `quarkus.langchain4j.mcp.tool-lab.tool-execution-timeout`: `120s`
- `quarkus.langchain4j.tool-lab.chat-model.provider`: `openai`
- `quarkus.langchain4j.openai.tool-lab.base-url`: `${QWEN_OPENAI_BASE_URL:http://localhost:8000/v1}`
- `quarkus.langchain4j.openai.tool-lab.chat-model.model-name`: `${QWEN_MODEL:qwen36-27b-all1000-plus-toollab-no-tool-fp8}`
- `quarkus.langchain4j.openai.tool-lab.timeout`: `120s`
- `quarkus.langchain4j.openai.tool-lab.api-key`: `${QWEN_OPENAI_API_KEY:dummy}`
- `%bedrock.quarkus.langchain4j.tool-lab.chat-model.provider`: `bedrock`
- `quarkus.langchain4j.bedrock.tool-lab.chat-model.model-id`: `${BEDROCK_GROUNDING_MODEL_ID:us.anthropic.claude-sonnet-4-6}`
- `quarkus.langchain4j.bedrock.tool-lab.aws.region`: `${AWS_REGION:us-east-2}`
- AWS credentials for Bedrock: default AWS credential provider chain; no credentials are hardcoded.

Optional MCP client logging:

```properties
quarkus.langchain4j.mcp.tool-lab.log-requests=true
quarkus.langchain4j.mcp.tool-lab.log-responses=true
```

Keep these disabled by default because logs may include prompts, tool arguments, and model payloads.

## Test

```shell
./mvnw -pl clients/quarkus-agent-client -am test
```
