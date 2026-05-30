#!/usr/bin/env python3
"""Generate a narrow SFT dataset for no-tool structured response envelopes."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


GENERATOR_VERSION = "no-tool-envelope-v1"
DATASET_ID = "tool-lab-no-tool-envelope"
EVAL_EXCLUSIONS = ["Write a short poem about cloud computing."]

SYSTEM_PROMPT = (
    "You are a tool-calling assistant for a compute-planning lab. "
    "Use compute tools only for compute-planning requests. "
    "If no compute-planning tool applies, answer with exactly one JSON object and no markdown."
)

NO_TOOL_MESSAGES = [
    "No compute-planning tool is needed for this request.",
    "This request does not require the compute-planning tools.",
    "No accelerated-compute planning tool applies to this request.",
    "This can be answered without invoking compute tools.",
    "The request is outside the compute-planning tool scope.",
    "No business tool is applicable for this non-compute request.",
    "The available compute tools are not relevant to this request.",
    "A direct structured response is appropriate because no tool applies.",
]

PROMPTS = [
    "Write a haiku about a quiet library at sunrise.",
    "Give me a two-line poem about fresh coffee.",
    "Draft a friendly birthday greeting for a coworker.",
    "Write a short limerick about a misplaced umbrella.",
    "Create a calm bedtime sentence for a child.",
    "Suggest a title for a mystery story set in a train station.",
    "Write a playful slogan for a neighborhood bake sale.",
    "Give me a one-sentence description of autumn leaves.",
    "Write a tiny scene where a painter chooses a new color.",
    "Compose a thank-you note for a helpful librarian.",
    "Give me a joke about calendars.",
    "Tell me a light pun about pencils.",
    "Write a funny caption for a photo of an empty mug.",
    "Give me a family-friendly joke about elevators.",
    "Make a playful riddle whose answer is a bookmark.",
    "Write a silly sentence about socks disappearing.",
    "Give me a gentle joke about Monday mornings.",
    "Invent a whimsical name for a sandwich shop.",
    "Write a one-line joke about a dictionary.",
    "Create a cheerful tagline for a lemonade stand.",
    "What is a simple way to remember the order of colors in a rainbow?",
    "Explain why people stretch before exercising in one sentence.",
    "What is the difference between a simile and a metaphor?",
    "Give me a plain-language definition of photosynthesis.",
    "Explain why the moon appears to change shape.",
    "What are three common ingredients in banana bread?",
    "Summarize the purpose of a table of contents.",
    "What does RSVP mean on an invitation?",
    "Explain what a library card is used for.",
    "What is a polite way to decline a meeting invitation?",
    "What is your favorite color?",
    "Which fictional detective do you find most interesting?",
    "Do you prefer tea or coffee?",
    "What kind of music is best for studying?",
    "Which season feels most relaxing?",
    "What makes a good opening sentence for a novel?",
    "Is handwritten mail still meaningful?",
    "What is a nice theme for a small dinner party?",
    "Which board game is good for a quiet evening?",
    "What makes a museum visit enjoyable?",
    "Can you diagnose this rash from a description?",
    "Should I invest my savings in a specific stock today?",
    "Can you write a legal contract for selling a house?",
    "What medicine should I take for chest pain?",
    "Can you predict tomorrow's lottery numbers?",
    "Should I quit my job based on one bad meeting?",
    "Can you identify a person from a private photo?",
    "What is the password for my neighbor's router?",
    "Can you guarantee that a business idea will succeed?",
    "Tell me who will win a local election next year.",
    "Write a short apology for arriving late to dinner.",
    "Draft a polite text asking a friend to return a book.",
    "Give me a concise meeting agenda for a book club.",
    "Write a welcome message for a community garden newsletter.",
    "Create a short reminder to water houseplants.",
    "Draft a note thanking a neighbor for collecting mail.",
    "Write a simple announcement for a lost scarf.",
    "Suggest three names for a pet goldfish.",
    "Create a subject line for a school bake sale email.",
    "Write a brief invitation to a weekend picnic.",
    "What are three ways to relax after a long day?",
    "Give me tips for organizing a kitchen drawer.",
    "Suggest a simple indoor activity for a rainy afternoon.",
    "How can I make a grocery list easier to follow?",
    "What are good manners when borrowing a book?",
    "Give me ideas for a handwritten holiday card.",
    "Suggest a simple morning routine for a busy parent.",
    "What are three ways to make a small room feel tidy?",
    "How do I politely ask someone to lower the music?",
    "What are easy themes for a family trivia night?",
    "What is a metaphor for patience?",
    "Write a tiny fable about a cautious gardener.",
    "Give me a one-sentence story about a lantern.",
    "Create a gentle compliment for a new artist.",
    "Write a short toast for a friend's promotion.",
    "Give me a non-technical analogy for teamwork.",
    "Suggest a name for a fictional mountain village.",
    "Write a brief journal prompt about gratitude.",
    "Create a cheerful sign for a community cleanup.",
    "Give me a one-paragraph description of a peaceful harbor.",
]


def assistant_target(index: int) -> str:
    payload = {
        "responseType": "no_tool_applicable",
        "message": NO_TOOL_MESSAGES[index % len(NO_TOOL_MESSAGES)],
        "claims": [],
        "missingFields": [],
    }
    return json.dumps(payload, separators=(",", ":"))


def build_records() -> list[dict]:
    records = []
    for index, prompt in enumerate(PROMPTS):
        records.append({
            "dialog": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": prompt},
                {"role": "assistant", "content": assistant_target(index)},
            ]
        })
    return records


def validate_records(records: list[dict]) -> None:
    if not 50 <= len(records) <= 100:
        raise ValueError(f"Expected 50-100 records, got {len(records)}")
    seen_prompts = set()
    for record in records:
        dialog = record.get("dialog")
        if not isinstance(dialog, list) or len(dialog) != 3:
            raise ValueError("Each record must contain exactly three dialog messages")
        roles = [message.get("role") for message in dialog]
        if roles != ["system", "user", "assistant"]:
            raise ValueError(f"Unexpected dialog roles: {roles}")
        user_prompt = dialog[1].get("content")
        if user_prompt in EVAL_EXCLUSIONS:
            raise ValueError(f"Eval prompt leaked into training data: {user_prompt}")
        if user_prompt in seen_prompts:
            raise ValueError(f"Duplicate user prompt: {user_prompt}")
        seen_prompts.add(user_prompt)
        assistant = json.loads(dialog[2].get("content", ""))
        if assistant.get("responseType") != "no_tool_applicable":
            raise ValueError("Assistant responseType must be no_tool_applicable")
        if not isinstance(assistant.get("message"), str) or not assistant["message"]:
            raise ValueError("Assistant message must be a non-empty string")
        if assistant.get("claims") != []:
            raise ValueError("Assistant claims must be []")
        if assistant.get("missingFields") != []:
            raise ValueError("Assistant missingFields must be []")


def write_jsonl(path: Path, records: list[dict]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [json.dumps(record, separators=(",", ":"), ensure_ascii=False) for record in records]
    content = "\n".join(lines) + "\n"
    path.write_text(content, encoding="utf-8")
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def write_manifest(path: Path, record_count: int, train_sha256: str) -> None:
    manifest = {
        "datasetId": DATASET_ID,
        "generatorVersion": GENERATOR_VERSION,
        "recordCount": record_count,
        "format": "jsonl.dialog",
        "trainSha256": f"sha256:{train_sha256}",
        "targetResponseType": "no_tool_applicable",
        "assistantRequiredFields": ["responseType", "message", "claims", "missingFields"],
        "heldOutEvalExclusions": EVAL_EXCLUSIONS,
        "notes": [
            "Preparation-only model customization candidate.",
            "Does not include compute.no-tool.001's exact eval prompt.",
            "Targets final assistant text envelope behavior, not tool-call token generation.",
        ],
    }
    path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_readme(path: Path, record_count: int) -> None:
    path.write_text(f"""# No-Tool Envelope SFT Dataset

This dataset is a narrow model-customization candidate for the tool-calling
consistency lab. It targets one behavior: when no compute-planning tool applies,
the assistant should emit the required structured JSON envelope instead of prose.

- Records: {record_count}
- Format: JSONL with `dialog` messages (`system`, `user`, `assistant`)
- Generator: `../generate_no_tool_envelope_sft.py`
- Held-out eval exclusion: `Write a short poem about cloud computing.`

Each assistant target is strict JSON only:

```json
{{"responseType":"no_tool_applicable","message":"...","claims":[],"missingFields":[]}}
```

This is preparation only. No training job has been submitted from this dataset.
""", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("training/no-tool-envelope"))
    parser.add_argument("--hyperpod-output", type=Path)
    args = parser.parse_args()

    records = build_records()
    validate_records(records)

    train_path = args.output_dir / "train.jsonl"
    train_sha256 = write_jsonl(train_path, records)
    write_manifest(args.output_dir / "manifest.json", len(records), train_sha256)
    write_readme(args.output_dir / "README.md", len(records))

    if args.hyperpod_output is not None:
        write_jsonl(args.hyperpod_output, records)

    print(f"Wrote {len(records)} records to {train_path}")
    print(f"trainSha256=sha256:{train_sha256}")


if __name__ == "__main__":
    main()
