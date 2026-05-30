# Tool Lab Strands Agents Client

This optional Python client demonstrates AWS Strands Agents consuming the Tool Lab MCP server through native MCP support. It is a framework demo only; it does not score benchmark behavior and does not replace `tool-lab-evaluator`.
It uses Qwen through the OpenAI-compatible adapter by default. A `bedrock` provider is available when you want the same agent and MCP tools to run through Bedrock Claude Sonnet.

## What It Uses

- `strands-agents`
- Strands `MCPClient`
- MCP `streamable_http_client`
- Strands `OpenAIModel` by default (Qwen/vLLM)
- Strands `BedrockModel` for the optional Bedrock path
- AWS default credential chain (only needed when using the Bedrock provider)
- `uv` for project and dependency management

## Configuration

Environment variables:

- `TOOL_LAB_MCP_URL`, default `http://localhost:8088/mcp`
- `TOOL_LAB_MODEL_PROVIDER`, default `qwen`; set to `bedrock` for Bedrock Claude Sonnet
- `AWS_REGION`, default `us-east-2`
- `BEDROCK_GROUNDING_MODEL_ID`, default `us.anthropic.claude-sonnet-4-6`
- `AWS_PROFILE`, optional, inherited by the AWS SDK credential chain
- `QWEN_OPENAI_BASE_URL`, default `http://localhost:8000/v1`
- `QWEN_MODEL`, default `qwen36-27b-all1000-plus-toollab-no-tool-fp8`
- `QWEN_OPENAI_API_KEY`, default `dummy`
- `QWEN_OPENAI_TIMEOUT_SECONDS`, default `120`

No credentials should be committed to this project.

## Run

Start the MCP server from the lab root:

```shell
cd mcp-tool-consistency-lab
./mvnw -pl tool-lab-mcp-server -am quarkus:dev
```

Run one scenario from this client directory:

```shell
uv run tool-lab-strands --scenario spec
uv run tool-lab-strands --scenario price
uv run tool-lab-strands --scenario no-tool
uv run tool-lab-strands --scenario serial
uv run tool-lab-strands --scenario parallel
uv run tool-lab-strands --scenario mixed-dag
uv run tool-lab-strands --scenario all
```

Example Qwen/vLLM live smoke command (default):

```shell
QWEN_OPENAI_BASE_URL=http://localhost:8000/v1 \
QWEN_MODEL=qwen36-27b-all1000-plus-toollab-no-tool-fp8 \
TOOL_LAB_MCP_URL=http://localhost:8088/mcp \
uv run tool-lab-strands --scenario all
```

Example Bedrock Sonnet live smoke command:

```shell
TOOL_LAB_MODEL_PROVIDER=bedrock \
AWS_PROFILE=your-aws-profile \
AWS_REGION=us-east-2 \
BEDROCK_GROUNDING_MODEL_ID=us.anthropic.claude-sonnet-4-6 \
TOOL_LAB_MCP_URL=http://localhost:8088/mcp \
uv run tool-lab-strands --scenario all
```

Set `QWEN_MODEL` to the model ID returned by `GET /v1/models` on your vLLM endpoint. The Qwen endpoint is only the chat model backend; MCP tool calls still go to `TOOL_LAB_MCP_URL`.

The `all` portability smoke runs six canned scenarios: exact specs, on-demand price, no-tool creative writing, a serial fit-then-price request, a comparison request that encourages independent lookups, and candidate comparison with specs, fit, price, then recommendation.

For the mixed-DAG benchmark task, the evaluator measures DAG shape via accepted-trace equivalence: missing evidence, collapsed layers, reordered layers, or premature recommendation fails scoring even if each tool call succeeds. This demo does not enforce that DAG at runtime; production deployments should pair benchmark measurement with an orchestrator/evidence ledger.

This client also includes a control-plane policy demo for the mixed-DAG recommendation case. A Strands `AfterModelCallEvent` hook validates the model's proposed tool-call plan and retries if it proposes `recommend_instance` before every candidate has spec, fit, and price evidence. The MCP server also enforces the same policy through native MCP tool guardrails.

## Test

```shell
uv run pytest
```

The unit tests do not make live MCP, Bedrock, or Qwen/vLLM calls.

## Boundary

This client does not expose tool-call traces and is not benchmark evidence. A completed scenario means the framework path returned a coherent answer through the selected model backend plus MCP; it does not prove trace equivalence. Use the evaluator `mcp-http` mode for trace evidence. Tool-level benchmark-grade evidence remains in the deterministic Java evaluator and its cache artifacts.
