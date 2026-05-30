# MCP Tool-Calling Consistency Lab

A deterministic lab for capturing, replaying, and regression-gating multi-tool agent failures using the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/).

Large language models are increasingly reliable at single tool calls. But when a task requires multiple tools — choosing the right ones, calling them in the right order, knowing when to stop — models make visible, reproducible mistakes. The [Berkeley Function-Calling Leaderboard (BFCL)](https://gorilla.cs.berkeley.edu/leaderboard.html) shows this gap clearly: top models score well on simple single-turn calls, but multi-tool, parallel, and multi-turn categories reveal persistent reliability gaps. This lab captures those failures, replays them without live model calls, and scores behavior against accepted traces.

## Architecture

Three consumers hit one MCP server. The evaluator is the benchmark source of truth. The agent clients prove the same tools work in production-shaped frameworks.

```
┌──────────────────────┐
│  Evaluator           │───┐
│  (benchmark)         │   │
└──────────────────────┘   │
                           │     ┌─────────────────────────┐     ┌──────────────────────┐
┌──────────────────────┐   ├────▶│  MCP Server             │────▶│  Domain              │
│  Quarkus AI          │───┤     │  shared tool surface    │     │  deterministic       │
│  (Java AI agent)     │   │     │  under test             │     │  compute backend     │
└──────────────────────┘   │     └─────────────────────────┘     └──────────────────────┘
                           │
┌──────────────────────┐   │
│  Strands Agent       │──-┘
│  (Python AI agent)   │
└──────────────────────┘
```

## How the Lab Works

The evaluator runs every task twice against the same MCP server:

1. **Expected replay** — execute the accepted (known-correct) trace → get expected final state
2. **Actual run** — let the model decide tool calls → get actual final state
3. **Compare** — trace, final state, schema validity, response envelope

If all match → pass. If not → the evaluator records exactly what went wrong.

## Quick Start

### Prerequisites

- Java 21+ ([SDKMAN](https://sdkman.io/usage/) recommended: `sdk install java 25.0.2-amzn`)
- Maven (or use the included `mvnw` wrapper)

### 1. Start the MCP server

```bash
./mvnw -pl tool-lab-mcp-server -am package
java -jar tool-lab-mcp-server/target/quarkus-app/quarkus-run.jar
```

The server starts on port 8088 with MCP endpoints at `http://localhost:8088/mcp`.

### 2. Run the evaluator (dry-run)

```bash
./mvnw -pl tool-lab-evaluator -am package
java -jar tool-lab-evaluator/target/quarkus-app/quarkus-run.jar \
  eval --dry-run --limit 1 --mcp-endpoint http://localhost:8088/mcp
```

Dry-run uses a mock model with canned tool calls but executes them against the real MCP server. Check the server terminal — you'll see the MCP traffic.

### 3. Enable MCP traffic logging

Add to `tool-lab-mcp-server/src/main/resources/application.properties`:

```properties
quarkus.mcp.server.traffic-logging.enabled=true
quarkus.mcp.server.traffic-logging.text-limit=1000
```

Rebuild and restart the server to see every JSON-RPC message.

## What the Evaluator Scores

| Dimension | What it checks |
|---|---|
| Schema valid | Tool call arguments match the JSON schema |
| Tool execution | Every tool call succeeded (or failed with the expected error code) |
| Tool selection | The model picked the right tools, no extras, no missing |
| Trace | Tool call sequence matches an accepted trace (order-sensitive for serial, order-insensitive within parallel groups) |
| Final state | Actual final state hash equals expected final state hash |
| Structured response | Final answer is valid structured JSON with required fields |
| Overall pass | All dimensions green |

## Remediation Ladder

The lab demonstrates that most multi-tool failures are fixable without model customization:

| Step | Fix | When to use | Example |
|---|---|---|---|
| 1 | Runtime configuration | Move provider output into the right channel | Enable `--tool-call-parser`, `enable_thinking=false`, and `preserve_thinking=false` so vLLM emits tool calls in content instead of reasoning |
| 2 | Prompt refinement | Influence when to stop, abstain, or avoid extra tools | "After a successful `get_instance_spec` result, do not call any tool again" |
| 3 | Tool-schema calibration | Treat true schema defaults as equivalent | Accept `{"purchaseOption":"on_demand"}` when the schema default is already `on_demand` |
| 4 | Orchestration rules | Enforce serial dependencies and parallel groups | Block `recommend_instance` until spec, fit, and price evidence exist for every candidate |
| 5 | Model customization (SFT) | Persistent protocol behavior that should travel with the model (where applicable — e.g. open-weight Qwen 3.6 27B possible, Sonnet 4.6 via Bedrock not possible) | 80 training examples teaching `{"responseType":"no_tool_applicable",...}` instead of plain text |
| 6 | Regression gating | Every fix must pass old green cases and fix old red cases | SFT fixed no-tool but regressed exact-spec → caught by evaluator before promotion |

## Module Structure

```
mcp-tool-consistency-lab/
├── tool-lab-contract/          Shared types: ToolCallResult, CanonicalJson, Hashing, ToolPolicy
├── tool-lab-domain/            Deterministic compute logic: specs, pricing, fit, plans
├── tool-lab-mcp-server/        Quarkus MCP server exposing domain tools
├── tool-lab-evaluator/         Benchmark harness: model loop, MCP execution, scoring
├── clients/
│   ├── quarkus-agent-client/   Java AI agent using Quarkus LangChain4j (Qwen by default, optional Bedrock Sonnet)
│   └── strands-agent-client/   Python AI agent using Strands Agents (Qwen by default, optional Bedrock Sonnet)
└── training/                   SFT dataset generator for no-tool envelope failures
```

**Dependency graph:**

- `evaluator` → `contract`
- `domain` → `contract`
- `mcp-server` → `contract` + `domain`
- `Java AI agent (quarkus)` → `contract` (shared policy types)
- `Python AI agent (strands)` → reads policy JSON from `contract` resources by path (no package dependency)

The evaluator has no compile-time dependency on `domain` or `mcp-server` — tool execution goes over MCP wire.

## Agent Clients

Both agent clients consume the same MCP server through Qwen (OpenAI-compatible) by default, with Bedrock Claude Sonnet available as an optional profile:

- **Quarkus AI** — `@RegisterAiService(modelName = "tool-lab")` + `@McpToolBox` + LLM output guardrail for recommendation preconditions. Use `-Dquarkus.profile=bedrock` to switch from Qwen to Bedrock.
- **Strands Agents** — Python agent with MCP tool provider + hook-based policy enforcement. Set `TOOL_LAB_MODEL_PROVIDER=bedrock` to switch from Qwen to Bedrock.

Supported scenarios: `spec`, `price`, `no-tool`, `serial`, `parallel`, `mixed-dag`.

See each client's README for setup and run instructions.

## Public Validation Harness

Use [docs/demo-harness/final-public-validation-prompt.md](docs/demo-harness/final-public-validation-prompt.md)
to validate a fresh public clone with a local MCP server, evaluator runs, and
Quarkus/Strands client smoke tests. The prompt is designed for local coding
assistants such as Codex, Claude, Kiro, or an agentic IDE.

## Optional: AgentCore Registry for Production Discovery

In production, clients can discover the MCP server through
[AWS AgentCore Registry](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/registry.html)
instead of hardcoding endpoints. The registry supports MCP-native discovery with
IAM or JWT authentication.

This lab runs locally without a registry. For production deployments, publish the
MCP server metadata to AgentCore Registry and let clients discover it there.

## License

This project is licensed under the MIT License.
