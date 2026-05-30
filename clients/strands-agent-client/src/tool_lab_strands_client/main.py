from __future__ import annotations

import argparse
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from tool_lab_strands_client.policy import CandidateRecommendationLlmGuardrail


CLIENT_MODE = "strands-agents-mcp"
DEFAULT_MCP_URL = "http://localhost:8088/mcp"
DEFAULT_REGION = "us-east-2"
DEFAULT_MODEL_ID = "us.anthropic.claude-sonnet-4-6"
DEFAULT_MODEL_PROVIDER = "qwen"
DEFAULT_QWEN_BASE_URL = "http://localhost:8000/v1"
DEFAULT_QWEN_MODEL_ID = "qwen36-27b-all1000-plus-toollab-no-tool-fp8"
SCENARIOS = ("spec", "price", "no-tool", "serial", "parallel", "mixed-dag")
SYSTEM_PROMPT = (
    Path(__file__).resolve().parents[4]
    / "tool-lab-contract/src/main/resources/prompts/tool-lab-baseline-system.txt"
).read_text(encoding="utf-8")


@dataclass(frozen=True)
class ToolLabConfig:
    mcp_url: str
    model_provider: str
    aws_region: str
    model_id: str
    aws_profile: str | None
    qwen_base_url: str
    qwen_model_id: str
    qwen_api_key: str
    qwen_timeout_seconds: float
    qwen_enable_thinking: bool
    qwen_preserve_thinking: bool


@dataclass(frozen=True)
class AgentTranscript:
    scenario: str
    user_prompt: str
    final_answer: str
    client_mode: str = CLIENT_MODE


def load_config(environ: dict[str, str] | None = None) -> ToolLabConfig:
    env = environ if environ is not None else os.environ
    return ToolLabConfig(
        mcp_url=env.get("TOOL_LAB_MCP_URL", DEFAULT_MCP_URL),
        model_provider=env.get("TOOL_LAB_MODEL_PROVIDER", DEFAULT_MODEL_PROVIDER).strip().lower(),
        aws_region=env.get("AWS_REGION", DEFAULT_REGION),
        model_id=env.get("BEDROCK_GROUNDING_MODEL_ID", DEFAULT_MODEL_ID),
        aws_profile=env.get("AWS_PROFILE"),
        qwen_base_url=env.get("QWEN_OPENAI_BASE_URL", DEFAULT_QWEN_BASE_URL),
        qwen_model_id=env.get("QWEN_MODEL", DEFAULT_QWEN_MODEL_ID),
        qwen_api_key=env.get("QWEN_OPENAI_API_KEY", "dummy"),
        qwen_timeout_seconds=float(env.get("QWEN_OPENAI_TIMEOUT_SECONDS", "120")),
        qwen_enable_thinking=bool_value(env.get("QWEN_ENABLE_THINKING", "false")),
        qwen_preserve_thinking=bool_value(env.get("QWEN_PRESERVE_THINKING", "false")),
    )


def selected_scenarios(scenario: str) -> list[str]:
    normalized = scenario.strip().lower()
    if normalized == "all":
        return list(SCENARIOS)
    if normalized in SCENARIOS:
        return [normalized]
    raise ValueError(f"Unknown scenario: {scenario}")


def prompt_for(scenario: str) -> str:
    return {
        "spec": "What are the exact specs for p5.48xlarge?",
        "price": "What is the on-demand price for p5.48xlarge?",
        "no-tool": "Write a short poem about cloud computing.",
        "serial": (
            "For a 70B parameter model in fp8 inference mode, check whether p5.48xlarge fits "
            "and then report its on-demand price."
        ),
        "parallel": "Compare p5.48xlarge and p5e.48xlarge on accelerator memory and on-demand monthly price.",
        "mixed-dag": (
            "Compare p5.48xlarge and p5e.48xlarge for a 70B BF16 fine-tuning workload. "
            "Check specs and fit for both candidates, compare their exact tool-returned prices, "
            "then recommend the cheapest valid option."
        ),
    }[scenario]


def run_scenario(scenario: str, config: ToolLabConfig) -> AgentTranscript:
    prompt = prompt_for(scenario)
    answer = invoke_agent(prompt, config)
    return AgentTranscript(scenario=scenario, user_prompt=prompt, final_answer=answer)


def invoke_agent(prompt: str, config: ToolLabConfig) -> str:
    from mcp.client.streamable_http import streamable_http_client
    from strands import Agent
    from strands.tools.mcp import MCPClient

    mcp_client = MCPClient(lambda: streamable_http_client(config.mcp_url))
    model = build_model(config)

    with mcp_client:
        tools = mcp_client.list_tools_sync()
        agent = Agent(
            model=model,
            tools=tools,
            system_prompt=SYSTEM_PROMPT,
            callback_handler=None,
            hooks=[CandidateRecommendationLlmGuardrail()],
        )
        return str(agent(prompt))


def build_model(config: ToolLabConfig) -> Any:
    if config.model_provider == "bedrock":
        from strands.models import BedrockModel

        return BedrockModel(
            model_id=config.model_id,
            region_name=config.aws_region,
            temperature=0.0,
            max_tokens=1024,
        )

    if config.model_provider == "qwen":
        from strands.models.openai import OpenAIModel

        return OpenAIModel(
            client_args={
                "base_url": config.qwen_base_url,
                "api_key": config.qwen_api_key,
                "timeout": config.qwen_timeout_seconds,
            },
            model_id=config.qwen_model_id,
            params={
                "temperature": 0.0,
                "max_tokens": 1024,
                "extra_body": {
                    "chat_template_kwargs": {
                        "enable_thinking": config.qwen_enable_thinking,
                        "preserve_thinking": config.qwen_preserve_thinking,
                    },
                },
            },
        )

    raise ValueError(f"Unknown model provider: {config.model_provider}")


def bool_value(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


def print_transcript(transcript: AgentTranscript) -> None:
    print(f"Scenario: {transcript.scenario}")
    print(f"User: {transcript.user_prompt}")
    print(f"Client mode: {transcript.client_mode}")
    print(f"Final: {transcript.final_answer}")
    print()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run AWS Strands Agents scenarios against the Tool Lab MCP server.")
    parser.add_argument(
        "--scenario",
        choices=(*SCENARIOS, "all"),
        default="all",
        help="Scenario to run: spec, price, no-tool, serial, parallel, mixed-dag, or all.",
    )
    return parser


def run(argv: Iterable[str] | None = None) -> int:
    args = build_parser().parse_args(list(argv) if argv is not None else None)
    config = load_config()
    for scenario in selected_scenarios(args.scenario):
        print_transcript(run_scenario(scenario, config))
    return 0


def main() -> None:
    raise SystemExit(run())
