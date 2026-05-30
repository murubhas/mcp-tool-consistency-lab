from strands.hooks import AfterModelCallEvent

from tool_lab_strands_client.policy import (
    CandidateRecommendationLlmGuardrail,
    EvidenceLedger,
    POLICY_ID,
    load_default_enforcer,
    load_policy,
)


CANDIDATES = ["p5.48xlarge", "p5e.48xlarge"]


def test_loads_shared_candidate_recommendation_policy() -> None:
    policy = load_policy()

    assert policy.policy_id == POLICY_ID
    assert policy.gated_tool == "recommend_instance"
    assert policy.gated_candidate_argument == "candidateInstanceTypes"
    assert [requirement.evidence for requirement in policy.required_evidence] == ["spec", "fit", "price"]


def test_premature_recommendation_is_blocked() -> None:
    enforcer = load_default_enforcer()
    ledger = EvidenceLedger()
    record_all_evidence(ledger, "p5.48xlarge")
    ledger.record_successful_tool_result("get_instance_spec", {"instanceType": "p5e.48xlarge"})
    ledger.record_successful_tool_result("check_model_fit", fit_args("p5e.48xlarge"))

    assert not enforcer.is_allowed(CANDIDATES, ledger)
    assert enforcer.missing_evidence(CANDIDATES, ledger) == ["p5e.48xlarge:price"]


def test_complete_candidate_evidence_allows_recommendation() -> None:
    enforcer = load_default_enforcer()
    ledger = EvidenceLedger()
    for candidate in CANDIDATES:
        record_all_evidence(ledger, candidate)

    assert enforcer.is_allowed_for_arguments(recommend_args(), ledger)


def test_mismatched_fit_evidence_does_not_authorize_recommendation() -> None:
    enforcer = load_default_enforcer()
    ledger = EvidenceLedger()
    for candidate in CANDIDATES:
        ledger.record_successful_tool_result("get_instance_spec", {"instanceType": candidate})
        ledger.record_successful_tool_result(
            "check_model_fit",
            {
                "instanceType": candidate,
                "modelBillionParameters": 34,
                "precision": "fp8",
                "mode": "inference",
            },
        )
        ledger.record_successful_tool_result("get_instance_price", {"instanceType": candidate})

    assert not enforcer.is_allowed_for_arguments(recommend_args(), ledger)
    assert enforcer.missing_evidence_for_arguments(recommend_args(), ledger) == [
        "p5.48xlarge:fit",
        "p5e.48xlarge:fit",
    ]


def test_llm_guardrail_retries_premature_recommendation_plan() -> None:
    guardrail = CandidateRecommendationLlmGuardrail()
    event = after_model_event("recommend_instance", recommend_args())

    guardrail.after_model_call(event)

    assert event.retry is True
    assert event.invocation_state["tool_lab_policy_block"].startswith("POLICY_BLOCKED")
    assert "p5.48xlarge:spec" in event.invocation_state["tool_lab_policy_block"]


def test_llm_guardrail_allows_recommendation_plan_after_evidence() -> None:
    ledger = EvidenceLedger()
    for candidate in CANDIDATES:
        record_all_evidence(ledger, candidate)
    guardrail = CandidateRecommendationLlmGuardrail(ledger=ledger)
    event = after_model_event("recommend_instance", recommend_args())

    guardrail.after_model_call(event)

    assert event.retry is False
    assert "tool_lab_policy_block" not in event.invocation_state


def test_llm_guardrail_records_evidence_tool_plans_for_later_recommendation_checks() -> None:
    guardrail = CandidateRecommendationLlmGuardrail()
    for candidate in CANDIDATES:
        guardrail.after_model_call(after_model_event("get_instance_spec", {"instanceType": candidate}))
        guardrail.after_model_call(after_model_event("check_model_fit", fit_args(candidate)))
        guardrail.after_model_call(after_model_event("get_instance_price", {"instanceType": candidate}))
    event = after_model_event("recommend_instance", recommend_args())

    guardrail.after_model_call(event)

    assert event.retry is False
    assert "tool_lab_policy_block" not in event.invocation_state


def test_llm_guardrail_ignores_non_tool_final_answer() -> None:
    guardrail = CandidateRecommendationLlmGuardrail()
    event = AfterModelCallEvent(
        agent=object(),
        invocation_state={},
        stop_response=AfterModelCallEvent.ModelStopResponse(
            message={"role": "assistant", "content": [{"text": "p5.48xlarge is the cheapest valid option."}]},
            stop_reason="end_turn",
        ),
    )

    guardrail.after_model_call(event)

    assert event.retry is False


def record_all_evidence(ledger: EvidenceLedger, instance_type: str) -> None:
    ledger.record_successful_tool_result("get_instance_spec", {"instanceType": instance_type})
    ledger.record_successful_tool_result("check_model_fit", fit_args(instance_type))
    ledger.record_successful_tool_result("get_instance_price", {"instanceType": instance_type})


def fit_args(instance_type: str) -> dict:
    return {
        "instanceType": instance_type,
        "modelBillionParameters": 70,
        "precision": "bf16",
        "mode": "fine_tuning",
    }


def recommend_args() -> dict:
    return {
        "candidateInstanceTypes": CANDIDATES,
        "modelBillionParameters": 70,
        "precision": "bf16",
        "mode": "fine_tuning",
        "optimizeFor": "cheapest",
    }


def after_model_event(tool_name: str, arguments: dict) -> AfterModelCallEvent:
    return AfterModelCallEvent(
        agent=object(),
        invocation_state={},
        stop_response=AfterModelCallEvent.ModelStopResponse(
            message={
                "role": "assistant",
                "content": [{"toolUse": {"toolUseId": "tool-1", "name": tool_name, "input": arguments}}],
            },
            stop_reason="tool_use",
        ),
    )
