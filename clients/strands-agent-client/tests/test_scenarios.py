from unittest.mock import patch
from pathlib import Path

import pytest

from tool_lab_strands_client.main import (
    AgentTranscript,
    SCENARIOS,
    SYSTEM_PROMPT,
    ToolLabConfig,
    build_parser,
    prompt_for,
    run,
    selected_scenarios,
)


def test_selected_scenarios_supports_single_cases() -> None:
    assert selected_scenarios("spec") == ["spec"]
    assert selected_scenarios("price") == ["price"]
    assert selected_scenarios("no-tool") == ["no-tool"]
    assert selected_scenarios("serial") == ["serial"]
    assert selected_scenarios("parallel") == ["parallel"]
    assert selected_scenarios("mixed-dag") == ["mixed-dag"]


def test_selected_scenarios_supports_all() -> None:
    assert selected_scenarios("all") == ["spec", "price", "no-tool", "serial", "parallel", "mixed-dag"]
    assert selected_scenarios("all") == list(SCENARIOS)


def test_selected_scenarios_rejects_unknown_case() -> None:
    with pytest.raises(ValueError, match="Unknown scenario"):
        selected_scenarios("bad")


def test_prompt_for_keeps_canned_prompts_stable() -> None:
    assert prompt_for("spec") == "What are the exact specs for p5.48xlarge?"
    assert prompt_for("price") == "What is the on-demand price for p5.48xlarge?"
    assert prompt_for("no-tool") == "Write a short poem about cloud computing."
    assert prompt_for("serial") == (
        "For a 70B parameter model in fp8 inference mode, check whether p5.48xlarge fits "
        "and then report its on-demand price."
    )
    assert prompt_for("parallel") == (
        "Compare p5.48xlarge and p5e.48xlarge on accelerator memory and on-demand monthly price."
    )
    assert prompt_for("mixed-dag") == (
        "Compare p5.48xlarge and p5e.48xlarge for a 70B BF16 fine-tuning workload. "
        "Check specs and fit for both candidates, compare their exact tool-returned prices, "
        "then recommend the cheapest valid option."
    )


def test_system_prompt_matches_contract_baseline_prompt() -> None:
    contract_prompt = (
        Path(__file__).resolve().parents[3]
        / "tool-lab-contract/src/main/resources/prompts/tool-lab-baseline-system.txt"
    ).read_text(encoding="utf-8")

    assert SYSTEM_PROMPT == contract_prompt


def test_help_mentions_mixed_dag_choice() -> None:
    help_text = build_parser().format_help()

    assert "mixed-dag" in help_text


def test_run_single_scenario_without_live_network() -> None:
    seen: list[str] = []

    def fake_run_scenario(scenario: str, config: ToolLabConfig) -> AgentTranscript:
        seen.append(scenario)
        return AgentTranscript(scenario=scenario, user_prompt="prompt", final_answer="answer")

    with patch("tool_lab_strands_client.main.run_scenario", side_effect=fake_run_scenario):
        assert run(["--scenario", "price"]) == 0

    assert seen == ["price"]


def test_run_all_scenarios_without_live_network() -> None:
    seen: list[str] = []

    def fake_run_scenario(scenario: str, config: ToolLabConfig) -> AgentTranscript:
        seen.append(scenario)
        return AgentTranscript(scenario=scenario, user_prompt="prompt", final_answer="answer")

    with patch("tool_lab_strands_client.main.run_scenario", side_effect=fake_run_scenario):
        assert run(["--scenario", "all"]) == 0

    assert seen == ["spec", "price", "no-tool", "serial", "parallel", "mixed-dag"]
