# No-Tool Envelope SFT Dataset

This dataset is a narrow model-customization candidate for the tool-calling
consistency lab. It targets one behavior: when no compute-planning tool applies,
the assistant should emit the required structured JSON envelope instead of prose.

- Records: 80
- Format: JSONL with `dialog` messages (`system`, `user`, `assistant`)
- Generator: `../generate_no_tool_envelope_sft.py`
- Held-out eval exclusion: `Write a short poem about cloud computing.`

Each assistant target is strict JSON only:

```json
{"responseType":"no_tool_applicable","message":"...","claims":[],"missingFields":[]}
```

This is preparation only. No training job has been submitted from this dataset.
