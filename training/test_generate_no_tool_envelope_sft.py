#!/usr/bin/env python3
"""Unit tests for the no-tool envelope SFT data generator."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import generate_no_tool_envelope_sft as generator


class NoToolEnvelopeGeneratorTest(unittest.TestCase):
    def test_records_are_valid_and_eval_prompt_is_excluded(self) -> None:
        records = generator.build_records()

        generator.validate_records(records)

        self.assertEqual(80, len(records))
        prompts = [record["dialog"][1]["content"] for record in records]
        self.assertNotIn("Write a short poem about cloud computing.", prompts)

    def test_assistant_outputs_are_strict_json_envelopes(self) -> None:
        for record in generator.build_records():
            assistant = json.loads(record["dialog"][2]["content"])
            self.assertEqual("no_tool_applicable", assistant["responseType"])
            self.assertIsInstance(assistant["message"], str)
            self.assertEqual([], assistant["claims"])
            self.assertEqual([], assistant["missingFields"])
            self.assertEqual(
                {"responseType", "message", "claims", "missingFields"},
                set(assistant.keys()),
            )

    def test_writer_outputs_train_manifest_and_readme(self) -> None:
        records = generator.build_records()
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)
            train_hash = generator.write_jsonl(output_dir / "train.jsonl", records)
            generator.write_manifest(output_dir / "manifest.json", len(records), train_hash)
            generator.write_readme(output_dir / "README.md", len(records))

            self.assertTrue((output_dir / "train.jsonl").exists())
            self.assertTrue((output_dir / "manifest.json").exists())
            self.assertTrue((output_dir / "README.md").exists())
            manifest = json.loads((output_dir / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(80, manifest["recordCount"])
            self.assertEqual(f"sha256:{train_hash}", manifest["trainSha256"])


if __name__ == "__main__":
    unittest.main()
