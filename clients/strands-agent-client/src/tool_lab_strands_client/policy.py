from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

from strands.hooks import AfterModelCallEvent, HookRegistry


POLICY_ID = "candidate-recommendation-v1"


@dataclass(frozen=True)
class EvidenceRequirement:
    tool: str
    argument: str
    evidence: str


@dataclass(frozen=True)
class ToolPolicy:
    policy_id: str
    gated_tool: str
    candidate_argument: str
    gated_candidate_argument: str
    required_evidence: tuple[EvidenceRequirement, ...]


@dataclass(frozen=True)
class RecommendationWorkload:
    candidates: tuple[str, ...]
    model_billion_parameters: int
    precision: str
    mode: str

    @property
    def fit_scope(self) -> tuple[int, str, str]:
        return (self.model_billion_parameters, self.precision, self.mode)


class EvidenceLedger:
    def __init__(self) -> None:
        self._evidence: set[tuple[str, str, tuple[int, str, str] | None]] = set()

    def record_successful_tool_result(self, tool_name: str, arguments: dict[str, Any]) -> None:
        instance_type = arguments.get("instanceType")
        if isinstance(instance_type, str) and instance_type:
            self._evidence.add((tool_name, instance_type, self._fit_scope(tool_name, arguments)))

    def has_evidence(self, tool_name: str, instance_type: str, workload: RecommendationWorkload | None = None) -> bool:
        if tool_name == "check_model_fit":
            if workload is None:
                return any(tool == tool_name and candidate == instance_type for tool, candidate, _ in self._evidence)
            return (tool_name, instance_type, workload.fit_scope) in self._evidence
        return (tool_name, instance_type, None) in self._evidence

    def _fit_scope(self, tool_name: str, arguments: dict[str, Any]) -> tuple[int, str, str] | None:
        if tool_name != "check_model_fit":
            return None
        return (
            int(arguments["modelBillionParameters"]),
            str(arguments["precision"]),
            str(arguments["mode"]),
        )


class CandidateRecommendationPolicyEnforcer:
    def __init__(self, policy: ToolPolicy) -> None:
        self.policy = policy

    def missing_evidence(
        self,
        candidates: Iterable[str],
        ledger: EvidenceLedger,
        workload: RecommendationWorkload | None = None,
    ) -> list[str]:
        missing: list[str] = []
        for candidate in candidates:
            for requirement in self.policy.required_evidence:
                if not ledger.has_evidence(requirement.tool, candidate, workload):
                    missing.append(f"{candidate}:{requirement.evidence}")
        return missing

    def is_allowed(self, candidates: Iterable[str], ledger: EvidenceLedger) -> bool:
        return not self.missing_evidence(candidates, ledger)

    def missing_evidence_for_arguments(self, arguments: dict[str, Any], ledger: EvidenceLedger) -> list[str]:
        workload = self.workload_from_recommend_arguments(arguments)
        return self.missing_evidence(workload.candidates, ledger, workload)

    def is_allowed_for_arguments(self, arguments: dict[str, Any], ledger: EvidenceLedger) -> bool:
        return not self.missing_evidence_for_arguments(arguments, ledger)

    def is_evidence_tool(self, tool_name: str) -> bool:
        return any(requirement.tool == tool_name for requirement in self.policy.required_evidence)

    def candidates_from_recommend_arguments(self, arguments: dict[str, Any]) -> list[str]:
        candidates = arguments.get(self.policy.gated_candidate_argument)
        if not isinstance(candidates, list) or not all(isinstance(candidate, str) for candidate in candidates):
            raise ValueError(f"Missing candidate list argument: {self.policy.gated_candidate_argument}")
        return candidates

    def workload_from_recommend_arguments(self, arguments: dict[str, Any]) -> RecommendationWorkload:
        return RecommendationWorkload(
            candidates=tuple(self.candidates_from_recommend_arguments(arguments)),
            model_billion_parameters=int(arguments["modelBillionParameters"]),
            precision=str(arguments["precision"]),
            mode=str(arguments["mode"]),
        )


class CandidateRecommendationLlmGuardrail:
    policy_blocked_prefix = "POLICY_BLOCKED"

    def __init__(
        self,
        enforcer: CandidateRecommendationPolicyEnforcer | None = None,
        ledger: EvidenceLedger | None = None,
    ) -> None:
        self.enforcer = enforcer if enforcer is not None else load_default_enforcer()
        self.ledger = ledger if ledger is not None else EvidenceLedger()

    def register_hooks(self, registry: HookRegistry) -> None:
        registry.add_callback(AfterModelCallEvent, self.after_model_call)

    def after_model_call(self, event: AfterModelCallEvent) -> None:
        if event.stop_response is None:
            return
        tool_uses = tool_uses_from_message(event.stop_response.message)
        for tool_use in tool_uses:
            if tool_use.get("name") != self.enforcer.policy.gated_tool:
                continue
            tool_input = tool_use.get("input", {})
            if not isinstance(tool_input, dict):
                continue
            missing = self.enforcer.missing_evidence_for_arguments(tool_input, self.ledger)
            if missing:
                event.retry = True
                event.invocation_state["tool_lab_policy_block"] = (
                    self.policy_blocked_prefix
                    + ": model proposed recommendation before required evidence: "
                    + ", ".join(missing)
                )
                return
        for tool_use in tool_uses:
            tool_name = tool_use.get("name")
            tool_input = tool_use.get("input", {})
            if isinstance(tool_name, str) and isinstance(tool_input, dict) and self.enforcer.is_evidence_tool(tool_name):
                self.ledger.record_successful_tool_result(tool_name, tool_input)


def tool_uses_from_message(message: dict[str, Any]) -> list[dict[str, Any]]:
    content = message.get("content", [])
    if not isinstance(content, list):
        return []
    tool_uses: list[dict[str, Any]] = []
    for block in content:
        if not isinstance(block, dict):
            continue
        tool_use = block.get("toolUse") or block.get("tool_use")
        if isinstance(tool_use, dict):
            tool_uses.append(tool_use)
    return tool_uses


def default_policy_path() -> Path:
    lab_root = Path(__file__).resolve().parents[4]
    return lab_root / "tool-lab-contract/src/main/resources/policies/candidate-recommendation-policy-v1.json"


def load_policy(path: Path | None = None) -> ToolPolicy:
    policy_path = path if path is not None else default_policy_path()
    raw = json.loads(policy_path.read_text(encoding="utf-8"))
    requirements = tuple(EvidenceRequirement(**entry) for entry in raw["requiredEvidence"])
    return ToolPolicy(
        policy_id=raw["policyId"],
        gated_tool=raw["gatedTool"],
        candidate_argument=raw["candidateArgument"],
        gated_candidate_argument=raw["gatedCandidateArgument"],
        required_evidence=requirements,
    )


def load_default_enforcer() -> CandidateRecommendationPolicyEnforcer:
    return CandidateRecommendationPolicyEnforcer(load_policy())
