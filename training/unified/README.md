# Unified SFT Dataset (1080 rows)

The unified dataset appends the 80 targeted no-tool envelope examples to the
1000-row business corpus.

## Purpose

This supports the model-customization story: existing behavior preservation plus
targeted protocol behavior. The model learns to produce the
`{"responseType":"no_tool_applicable",...}` JSON envelope when no tool applies,
while retaining its existing domain performance on compute-planning tasks.

## Composition

| Source | Rows | Purpose |
|--------|------|---------|
| `training/business-corpus/train-all-1000.jsonl` | 1000 | Behavior preservation |
| `training/no-tool-envelope/train.jsonl` | 80 | Targeted no-tool envelope protocol |
| **Total** | **1080** | Unified SFT dataset |

## Held-out Eval Prompt

The evaluator task `compute.no-tool.001` uses the prompt:

> Write a short poem about cloud computing.

This prompt is explicitly excluded from the 80 training rows to prevent data
leakage between training and evaluation.

## Regeneration

From the lab root:

```bash
python3 training/build_unified_no_tool_dataset.py
```

This reads both source datasets, validates them, and writes:

- `training/unified/train-all-1000-plus-no-tool-1080.jsonl`
- `training/unified/manifest.json`

## Note

This dataset is not benchmark evidence. The evaluator (`tool-lab-evaluator`)
remains the benchmark source of truth for tool-calling correctness. Training
execution (SFT jobs, quantization, deployment) is outside this repository.
