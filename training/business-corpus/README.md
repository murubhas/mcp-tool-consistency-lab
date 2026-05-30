# Business Corpus (1000 rows)

This is the public business/base SFT corpus used as the behavior preservation set
during targeted model customization.

## Purpose

When fine-tuning for a specific protocol behavior (such as the no-tool response
envelope), the base corpus ensures the model retains its existing capabilities on
domain-relevant tasks. The 1000 rows cover accelerated compute instance selection,
spec retrieval, pricing, model-fit calculations, and planning workflows.

## Source And Authority

The records are intended for model-training and demo use only. Although the
corpus uses public cloud-computing concepts and publicly discussable service
patterns, portions of the data were synthetically generated, paraphrased, or
made fictitious to create broad training coverage and avoid publishing private
material.

Do not treat this corpus as an authoritative source for AWS service behavior,
pricing, support processes, quotas, capacity, or operational guidance. For real
decisions, use the official AWS documentation, pricing pages, service APIs, and
your account-specific AWS guidance.

## Format

JSONL, one record per line. Each record has a `dialog` key containing a list of
`{role, content}` message objects.

## Record Count

1000

## Governance

This corpus was scrubbed for private identifiers, credentials, local paths, and
internal references before publication. The scrub checked for:

- Email addresses
- AWS account IDs, ARNs
- Local filesystem paths
- Internal hostnames
- Credentials (access key prefixes, secrets, tokens, passwords)
- Private repository references

False-positive hits from public/reference content were redacted in the published
copy where useful, including support-contact email strings and public pricing
URLs. Remaining broad keyword hits are domain-relevant technical language such as
ML tokens/sec and hardware internal storage descriptions.

## Note

This dataset is not benchmark evidence. The evaluator (`tool-lab-evaluator`)
remains the benchmark source of truth for tool-calling correctness.
