#!/usr/bin/env python3
"""Build the unified 1000 business + 80 no-tool envelope SFT dataset."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


GENERATOR_VERSION = "all1000-plus-no-tool-envelope-v1"
HELD_OUT_EVAL_PROMPT = "Write a short poem about cloud computing."


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return f"sha256:{digest.hexdigest()}"


def read_jsonl_lines(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    if any(not line.strip() for line in lines):
        raise ValueError(f"{path} contains blank JSONL lines")
    for line_number, line in enumerate(lines, start=1):
        try:
            json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(f"{path}:{line_number} is not valid JSON") from error
    return lines


def validate_no_tool_rows(no_tool_lines: list[str]) -> None:
    prompts = set()
    for line_number, line in enumerate(no_tool_lines, start=1):
        record = json.loads(line)
        dialog = record.get("dialog")
        if not isinstance(dialog, list) or len(dialog) != 3:
            raise ValueError(f"no-tool row {line_number} must contain a three-message dialog")
        if [message.get("role") for message in dialog] != ["system", "user", "assistant"]:
            raise ValueError(f"no-tool row {line_number} has unexpected roles")
        user_prompt = dialog[1].get("content")
        if user_prompt == HELD_OUT_EVAL_PROMPT:
            raise ValueError("compute.no-tool.001 eval prompt leaked into no-tool training rows")
        if user_prompt in prompts:
            raise ValueError(f"duplicate no-tool user prompt: {user_prompt}")
        prompts.add(user_prompt)
        assistant = json.loads(dialog[2].get("content", ""))
        if set(assistant) != {"responseType", "message", "claims", "missingFields"}:
            raise ValueError(f"no-tool row {line_number} assistant envelope has unexpected fields")
        if assistant["responseType"] != "no_tool_applicable":
            raise ValueError(f"no-tool row {line_number} responseType is not no_tool_applicable")
        if not isinstance(assistant["message"], str) or not assistant["message"]:
            raise ValueError(f"no-tool row {line_number} message must be non-empty")
        if assistant["claims"] != [] or assistant["missingFields"] != []:
            raise ValueError(f"no-tool row {line_number} claims and missingFields must be []")


def write_unified_dataset(output_path: Path, business_lines: list[str], no_tool_lines: list[str]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    content = "\n".join([*business_lines, *no_tool_lines]) + "\n"
    output_path.write_text(content, encoding="utf-8")


def write_manifest(
        manifest_path: Path,
        *,
        business_path: Path,
        no_tool_path: Path,
        unified_path: Path,
        business_count: int,
        no_tool_count: int,
) -> None:
    manifest = {
        "datasetId": "train-all-1000-plus-toollab-no-tool-1080",
        "generatorVersion": GENERATOR_VERSION,
        "composition": "append",
        "businessExamples": {
            "path": str(business_path),
            "lineCount": business_count,
            "sha256": sha256_file(business_path),
        },
        "toolLabNoToolExamples": {
            "path": str(no_tool_path),
            "lineCount": no_tool_count,
            "sha256": sha256_file(no_tool_path),
            "heldOutEvalPromptExcluded": HELD_OUT_EVAL_PROMPT,
            "targetResponseType": "no_tool_applicable",
        },
        "unifiedDataset": {
            "path": str(unified_path),
            "lineCount": business_count + no_tool_count,
            "sha256": sha256_file(unified_path),
        },
        "notes": [
            "Preserves the 1000 business JSONL records first, then appends the 80 tool-lab no-tool rows.",
            "Preparation only: no HyperPod/SageMaker training job has been submitted.",
            "This unified dataset supports a clean SFT story: existing business corpus plus targeted tool-reliability examples.",
        ],
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def build(args: argparse.Namespace) -> None:
    business_lines = read_jsonl_lines(args.business_data)
    no_tool_lines = read_jsonl_lines(args.no_tool_data)
    if len(business_lines) != 1000:
        raise ValueError(f"Expected 1000 business rows, got {len(business_lines)}")
    if len(no_tool_lines) != 80:
        raise ValueError(f"Expected 80 no-tool rows, got {len(no_tool_lines)}")
    validate_no_tool_rows(no_tool_lines)
    write_unified_dataset(args.output, business_lines, no_tool_lines)
    unified_lines = read_jsonl_lines(args.output)
    if len(unified_lines) != 1080:
        raise ValueError(f"Expected 1080 unified rows, got {len(unified_lines)}")
    write_manifest(
        args.manifest,
        business_path=args.business_data,
        no_tool_path=args.no_tool_data,
        unified_path=args.output,
        business_count=len(business_lines),
        no_tool_count=len(no_tool_lines),
    )
    print(f"Wrote {len(unified_lines)} records to {args.output}")
    print(f"businessSha256={sha256_file(args.business_data)}")
    print(f"noToolSha256={sha256_file(args.no_tool_data)}")
    print(f"unifiedSha256={sha256_file(args.output)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--business-data",
        type=Path,
        default=Path("training/business-corpus/train-all-1000.jsonl"),
    )
    parser.add_argument(
        "--no-tool-data",
        type=Path,
        default=Path("training/no-tool-envelope/train.jsonl"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("training/unified/train-all-1000-plus-no-tool-1080.jsonl"),
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("training/unified/manifest.json"),
    )
    build(parser.parse_args())


if __name__ == "__main__":
    main()
