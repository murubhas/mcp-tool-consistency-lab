# Final Public Validation Harness

This prompt can be pasted into a local coding assistant such as Codex, Claude,
Kiro, or another agentic IDE to validate the public repository from a fresh
clone.

The harness verifies:

- Java build
- Qwen endpoint health
- local MCP server startup
- evaluator pass/fail behavior
- Quarkus client smoke
- Strands client smoke

The evaluator is the benchmark source of truth. The agent clients are
production-shaped smoke tests that prove the same MCP surface works from Java
and Python frameworks.

## Assumptions

The validation uses two OpenAI-compatible Qwen/vLLM endpoints already exposed on
the local machine:

| Endpoint | Role | Expected model |
|---|---|---|
| `http://127.0.0.1:38000/v1` | baseline | `qwen36-27b-all1000-fp8` |
| `http://127.0.0.1:48000/v1` | SFT/fixed | `qwen36-27b-all1000-plus-toollab-no-tool-fp8` |

If your ports or model IDs differ, update the prompt before running it.

## Provider Choice

The baseline/SFT comparison requires an open-weight or otherwise customizable
model, because the demo validates a behavior learned through supervised
fine-tuning. Qwen is used here because it can be hosted behind an
OpenAI-compatible endpoint and customized for the no-tool JSON response.

If Qwen endpoints are not available, you can still run the lab with a hosted
model such as Claude Sonnet on Bedrock for evaluator/client smoke tests. That
path validates MCP tool behavior, prompts, schemas, guardrails, and replay, but
it will not reproduce the SFT before/after comparison because closed-weight
hosted models generally cannot be fine-tuned by this lab.

Use the Bedrock path when you want to test the tool-calling harness without
hosting Qwen. Use the Qwen path when you want to demonstrate model
customization as part of the remediation ladder.

## Prerequisites

Before running the harness, make sure the machine has:

- Git
- Java 21 or newer
- Maven, or permission to use the repository's `./mvnw` wrapper
- curl
- lsof
- Python 3.11 or newer
- uv, for the Strands client smoke tests
- Two reachable OpenAI-compatible Qwen/vLLM endpoints, one baseline and one
  SFT/fixed model

The Strands client section can be skipped if `uv` is unavailable, but the Java
build, MCP server, evaluator, and Quarkus client sections require Java and the
Maven wrapper to work.

## Prompt

```text
Please run a final public-repo validation harness.

Goal:
Validate that the public repository can be cloned fresh, built, started, and
used to reproduce the two demo cases across all three paths:

1. Evaluator
2. Quarkus agent client
3. Strands agent client

Live Qwen endpoints:

- Baseline model: 127.0.0.1:38000
  model: qwen36-27b-all1000-fp8

- SFT/fixed model: 127.0.0.1:48000
  model: qwen36-27b-all1000-plus-toollab-no-tool-fp8

Tasks/scenarios:

- spec:
  evaluator task: compute.single.spec.001
  client scenario: spec

- no-tool:
  evaluator task: compute.no-tool.001
  client scenario: no-tool

Important:
Use only the public repo clone under /tmp. Do not use any private checkout for
build or execution.

Repo:
https://github.com/murubhas/mcp-tool-consistency-lab

Step 1: Verify local prerequisites

Check the following commands before cloning:

git --version
java -version
curl --version
lsof -v
python3 --version
uv --version

Report the detected versions.

If git, Java, curl, lsof, or python3 is missing, stop and report the missing
prerequisite.

If uv is missing, continue with the Java build, MCP server, evaluator, and
Quarkus client sections, but mark the Strands section as SKIPPED because uv is
not installed.

Step 2: Clone fresh

rm -rf /tmp/mcp-tool-consistency-lab-public-final
git clone https://github.com/murubhas/mcp-tool-consistency-lab /tmp/mcp-tool-consistency-lab-public-final
cd /tmp/mcp-tool-consistency-lab-public-final

Step 3: Build Java modules

./mvnw -q package

If build fails, stop and report the exact failure.

Step 4: Verify Qwen endpoint health

curl -sS --max-time 5 http://127.0.0.1:38000/v1/models
curl -sS --max-time 5 http://127.0.0.1:48000/v1/models

Confirm expected model IDs:

- 38000 -> qwen36-27b-all1000-fp8
- 48000 -> qwen36-27b-all1000-plus-toollab-no-tool-fp8

If either endpoint is down or returns the wrong model, stop and report.

Step 5: Start MCP server from the public clone

Check whether port 8088 is already occupied:

lsof -nP -iTCP:8088 -sTCP:LISTEN

If occupied, report the process/PID and ask before killing anything.

If free, start the packaged MCP server jar, not Quarkus dev mode:

java -jar tool-lab-mcp-server/target/quarkus-app/quarkus-run.jar

Run it in the background and capture logs to:

/tmp/mcp-tool-consistency-lab-public-final-mcp.log

Verify the MCP endpoint:

curl -i --max-time 5 http://127.0.0.1:8088/mcp

Expected raw GET probe:
HTTP 405 Method Not Allowed

Step 6: Run evaluator path

Use these common flags for every evaluator run:

--dry-run=false
--model qwen
--prompt-variant baseline
--tool-execution mcp-http
--mcp-endpoint http://127.0.0.1:8088/mcp
--use-cache=false
--qwen-enable-thinking=false
--qwen-preserve-thinking=false
--fail-fast

Evaluator run A: baseline spec

java -jar tool-lab-evaluator/target/quarkus-app/quarkus-run.jar \
  eval --dry-run=false --model qwen \
  --endpoint http://127.0.0.1:38000/v1/chat/completions \
  --model-id qwen36-27b-all1000-fp8 \
  --served-model-name qwen36-27b-all1000-fp8 \
  --task-id compute.single.spec.001 \
  --prompt-variant baseline \
  --tool-execution mcp-http \
  --mcp-endpoint http://127.0.0.1:8088/mcp \
  --use-cache=false \
  --qwen-enable-thinking=false \
  --qwen-preserve-thinking=false \
  --fail-fast

Evaluator run B: fixed/SFT spec

java -jar tool-lab-evaluator/target/quarkus-app/quarkus-run.jar \
  eval --dry-run=false --model qwen \
  --endpoint http://127.0.0.1:48000/v1/chat/completions \
  --model-id qwen36-27b-all1000-plus-toollab-no-tool-fp8 \
  --served-model-name qwen36-27b-all1000-plus-toollab-no-tool-fp8 \
  --task-id compute.single.spec.001 \
  --prompt-variant baseline \
  --tool-execution mcp-http \
  --mcp-endpoint http://127.0.0.1:8088/mcp \
  --use-cache=false \
  --qwen-enable-thinking=false \
  --qwen-preserve-thinking=false \
  --fail-fast

Evaluator run C: baseline no-tool

Use the same baseline endpoint/model as run A, but change:

--task-id compute.no-tool.001

Evaluator run D: fixed/SFT no-tool

Use the same fixed endpoint/model as run B, but change:

--task-id compute.no-tool.001

For each evaluator run, capture:

- PASS/FAIL
- tool sequence
- final responseType
- final message excerpt
- result directory

Step 7: Run Quarkus agent client path

Use the packaged jar, not Quarkus dev mode.

Quarkus baseline spec:

TOOL_LAB_MCP_URL=http://127.0.0.1:8088/mcp \
QWEN_OPENAI_BASE_URL=http://127.0.0.1:38000/v1 \
QWEN_OPENAI_API_KEY=dummy \
QWEN_MODEL=qwen36-27b-all1000-fp8 \
java -jar clients/quarkus-agent-client/target/quarkus-app/quarkus-run.jar \
  --scenario spec

Quarkus fixed/SFT spec:

TOOL_LAB_MCP_URL=http://127.0.0.1:8088/mcp \
QWEN_OPENAI_BASE_URL=http://127.0.0.1:48000/v1 \
QWEN_OPENAI_API_KEY=dummy \
QWEN_MODEL=qwen36-27b-all1000-plus-toollab-no-tool-fp8 \
java -jar clients/quarkus-agent-client/target/quarkus-app/quarkus-run.jar \
  --scenario spec

Quarkus baseline no-tool:

TOOL_LAB_MCP_URL=http://127.0.0.1:8088/mcp \
QWEN_OPENAI_BASE_URL=http://127.0.0.1:38000/v1 \
QWEN_OPENAI_API_KEY=dummy \
QWEN_MODEL=qwen36-27b-all1000-fp8 \
java -jar clients/quarkus-agent-client/target/quarkus-app/quarkus-run.jar \
  --scenario no-tool

Quarkus fixed/SFT no-tool:

TOOL_LAB_MCP_URL=http://127.0.0.1:8088/mcp \
QWEN_OPENAI_BASE_URL=http://127.0.0.1:48000/v1 \
QWEN_OPENAI_API_KEY=dummy \
QWEN_MODEL=qwen36-27b-all1000-plus-toollab-no-tool-fp8 \
java -jar clients/quarkus-agent-client/target/quarkus-app/quarkus-run.jar \
  --scenario no-tool

For each Quarkus run, capture:

- scenario
- endpoint/model
- final response
- final responseType if structured JSON exists
- whether MCP tool calls occurred from logs
- any unexpected exception

Step 8: Run Strands agent client path

From the public clone, enter the Strands client directory:

cd /tmp/mcp-tool-consistency-lab-public-final/clients/strands-agent-client

If uv was not available during the prerequisite check, skip this section and
mark Strands as SKIPPED.

Strands baseline spec:

TOOL_LAB_MODEL_PROVIDER=qwen \
TOOL_LAB_MCP_URL=http://127.0.0.1:8088/mcp \
QWEN_OPENAI_BASE_URL=http://127.0.0.1:38000/v1 \
QWEN_OPENAI_API_KEY=dummy \
QWEN_MODEL=qwen36-27b-all1000-fp8 \
uv run tool-lab-strands --scenario spec

Strands fixed/SFT spec:

TOOL_LAB_MODEL_PROVIDER=qwen \
TOOL_LAB_MCP_URL=http://127.0.0.1:8088/mcp \
QWEN_OPENAI_BASE_URL=http://127.0.0.1:48000/v1 \
QWEN_OPENAI_API_KEY=dummy \
QWEN_MODEL=qwen36-27b-all1000-plus-toollab-no-tool-fp8 \
uv run tool-lab-strands --scenario spec

Strands baseline no-tool:

TOOL_LAB_MODEL_PROVIDER=qwen \
TOOL_LAB_MCP_URL=http://127.0.0.1:8088/mcp \
QWEN_OPENAI_BASE_URL=http://127.0.0.1:38000/v1 \
QWEN_OPENAI_API_KEY=dummy \
QWEN_MODEL=qwen36-27b-all1000-fp8 \
uv run tool-lab-strands --scenario no-tool

Strands fixed/SFT no-tool:

TOOL_LAB_MODEL_PROVIDER=qwen \
TOOL_LAB_MCP_URL=http://127.0.0.1:8088/mcp \
QWEN_OPENAI_BASE_URL=http://127.0.0.1:48000/v1 \
QWEN_OPENAI_API_KEY=dummy \
QWEN_MODEL=qwen36-27b-all1000-plus-toollab-no-tool-fp8 \
uv run tool-lab-strands --scenario no-tool

For each Strands run, capture:

- scenario
- endpoint/model
- final response
- final responseType if structured JSON exists
- whether MCP tool calls occurred from logs
- note that Strands may use streaming SSE to the Qwen endpoint; that is OK

Step 9: Report in tables

Table 1: Infrastructure

Columns:

- public clone path
- prerequisite versions
- build status
- 38000 health
- 48000 health
- MCP health
- MCP log path

Table 2: Evaluator results

Columns:

- endpoint
- model
- task
- PASS/FAIL
- tool sequence
- final responseType
- message excerpt
- result directory

Table 3: Client smoke results

Columns:

- client: Quarkus or Strands
- endpoint
- model
- scenario
- tool behavior observed
- final responseType
- message excerpt
- status

Expected demo story:

Spec:

- The model should use the spec tool and produce a final answer.
- If an endpoint makes an unexpected pricing call, report it clearly. Do not hide it.
- Note: the final answer may mention price if `get_instance_spec` returns price fields in
  its structured payload. That is not an additional pricing tool call.

No-tool:

- Baseline should call no tools but may fail the structured response contract.
- SFT/fixed should call no tools and return the required JSON response with
  responseType no_tool_applicable.

Step 10: Preserve artifacts

Preserve:

- evaluator result directories
- MCP log file
- any relevant raw request/response log paths if visible
- stdout snippets for the 12 runs

Step 11: Cleanup

Stop only the MCP server process started from this public clone.
Do not stop the Qwen endpoints.

Verify 8088 is no longer listening:

lsof -nP -iTCP:8088 -sTCP:LISTEN

Final answer should include:

- build status
- endpoint health
- MCP health
- evaluator table
- Quarkus/Strands table
- unexpected behavior
- artifact paths
- cleanup status
```

## Optional Sample Result

This is representative output from one successful local run. Timestamps, result
directories, and message wording will vary.

### Infrastructure

| Item | Status |
|---|---|
| Public clone path | `/tmp/mcp-tool-consistency-lab-public-final` |
| Build status | SUCCESS |
| 38000 health | UP - `qwen36-27b-all1000-fp8` |
| 48000 health | UP - `qwen36-27b-all1000-plus-toollab-no-tool-fp8` |
| MCP health | UP, started from public clone |
| MCP log path | `/tmp/mcp-tool-consistency-lab-public-final-mcp.log` |

### Evaluator Results

| Endpoint | Model | Task | Result | Tools | Final responseType | Message excerpt |
|---|---|---|---|---|---|---|
| 38000 | baseline | spec.001 | PASS | `get_instance_spec` | `final_answer` | `The p5.48xlarge has 8 H100 accelerators...` |
| 48000 | SFT | spec.001 | PASS | `get_instance_spec` | `final_answer` | `The p5.48xlarge has 8 H100 GPUs with 640 GiB...` |
| 38000 | baseline | no-tool.001 | FAIL | none | decode failure/no JSON | prose instead of required JSON response |
| 48000 | SFT | no-tool.001 | PASS | none | `no_tool_applicable` | `No tool is needed for this request.` |

### Client Smoke Results

| Client | Endpoint | Model | Scenario | Tools observed | Final responseType | Status |
|---|---|---|---|---|---|---|
| Quarkus | 38000 | baseline | spec | `get_instance_spec` via MCP | `final_answer` | OK |
| Quarkus | 48000 | SFT | spec | `get_instance_spec` via MCP | `final_answer` | OK |
| Quarkus | 38000 | baseline | no-tool | none | no JSON | Fails contract |
| Quarkus | 48000 | SFT | no-tool | none | `no_tool_applicable` | OK |
| Strands | 38000 | baseline | spec | `get_instance_spec` via MCP | `final_answer` | OK |
| Strands | 48000 | SFT | spec | `get_instance_spec` via MCP | `final_answer` | OK |
| Strands | 38000 | baseline | no-tool | none | no JSON | Fails contract |
| Strands | 48000 | SFT | no-tool | none | `no_tool_applicable` | OK |

Expected conclusion:

- Spec succeeds on both models with the spec tool only.
- No-tool exposes the model-customization contrast: baseline avoids tools but
  fails the required JSON response; SFT avoids tools and returns the required
  `no_tool_applicable` JSON object.
