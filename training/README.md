# Training Artifacts

This directory holds preparation-only training artifacts for the tool-calling
consistency lab.

## Structure

```
training/
├── business-corpus/        1000-row base behavior preservation corpus
│   ├── train-all-1000.jsonl
│   ├── manifest.json
│   └── README.md
├── no-tool-envelope/       80-row targeted no-tool response envelope dataset
│   ├── train.jsonl
│   ├── manifest.json
│   └── README.md
├── unified/                1080-row combined dataset (generated)
│   ├── train-all-1000-plus-no-tool-1080.jsonl
│   ├── manifest.json
│   └── README.md
├── build_unified_no_tool_dataset.py    Generates the unified dataset
├── generate_no_tool_envelope_sft.py    Generates the 80 no-tool examples
├── test_build_unified_no_tool_dataset.py
├── test_generate_no_tool_envelope_sft.py
└── README.md               This file
```

## Regenerating the Unified Dataset

```bash
python3 training/build_unified_no_tool_dataset.py
```

This reads both source datasets, validates them (JSON format, record counts,
held-out eval prompt exclusion, response envelope structure), and writes the
unified 1080-row dataset plus its manifest.

## Governance

All training data was scrubbed for private identifiers, credentials, local
paths, and internal references before publication. See each subdirectory's
README for scrub details.

## Boundary

No training job is submitted from this directory. The deterministic evaluator
remains the benchmark source of truth. Training execution (SFT jobs,
quantization, deployment) is outside this repository. The lab owns the dataset
and manifest, then consumes any trained model through provider endpoints.
