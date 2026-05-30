#!/usr/bin/env python3
"""Unit tests for the unified no-tool dataset builder."""

from __future__ import annotations

import json
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path

import build_unified_no_tool_dataset as builder
import generate_no_tool_envelope_sft as no_tool_generator


class UnifiedDatasetBuilderTest(unittest.TestCase):
    def test_build_appends_no_tool_rows_and_writes_hash_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            business = root / "business.jsonl"
            no_tool = root / "no-tool.jsonl"
            output = root / "unified.jsonl"
            manifest = root / "manifest.json"

            business.write_text(
                "".join(json.dumps({"dialog": [{"role": "user", "content": f"business {index}"}]}) + "\n"
                        for index in range(1000)),
                encoding="utf-8",
            )
            no_tool_generator.write_jsonl(no_tool, no_tool_generator.build_records())

            builder.build(Namespace(
                business_data=business,
                no_tool_data=no_tool,
                output=output,
                manifest=manifest,
            ))

            lines = output.read_text(encoding="utf-8").splitlines()
            self.assertEqual(1080, len(lines))
            self.assertEqual(json.loads(business.read_text(encoding="utf-8").splitlines()[0]), json.loads(lines[0]))
            self.assertEqual(
                json.loads(no_tool.read_text(encoding="utf-8").splitlines()[0]),
                json.loads(lines[1000]),
            )
            data = json.loads(manifest.read_text(encoding="utf-8"))
            self.assertEqual(1000, data["businessExamples"]["lineCount"])
            self.assertEqual(80, data["toolLabNoToolExamples"]["lineCount"])
            self.assertEqual(1080, data["unifiedDataset"]["lineCount"])
            self.assertTrue(data["unifiedDataset"]["sha256"].startswith("sha256:"))

    def test_validate_no_tool_rows_rejects_held_out_eval_prompt(self) -> None:
        records = no_tool_generator.build_records()
        records[0]["dialog"][1]["content"] = builder.HELD_OUT_EVAL_PROMPT
        lines = [json.dumps(record) for record in records]

        with self.assertRaisesRegex(ValueError, "eval prompt leaked"):
            builder.validate_no_tool_rows(lines)


if __name__ == "__main__":
    unittest.main()
