# ADR-0002: Java computes derived fields, not the LLM

**Status**: Accepted

## Context

The task spec's output schema for Decline Code Mapper includes a `summary` object with counts
(`total_codes`, `high_confidence`, `needs_review`, `unmapped`) and a per-mapping `needs_human_review` boolean.
The naive approach is to ask the LLM to produce these values alongside the mappings.

## Decision

The LLM produces only the raw `mappings` array. Java computes everything derived:
- `needs_human_review` = `confidence == "low"` (a deterministic rule, not a judgment call)
- `summary.total_codes` = size of the parsed codes list
- `summary.high_confidence` = count of mappings where confidence is `"high"`
- `summary.needs_review` = count of mappings where `needs_human_review` is true
- `summary.unmapped` = total codes minus successfully mapped codes

## Why

LLMs count unreliably. Asking a model to count its own outputs introduces a class of errors that are entirely
avoidable: off-by-one counts, hallucinated totals, `needs_human_review` set to `false` while the reasoning
describes genuine ambiguity. These fields are mechanical aggregations of data the application already holds.
Computing them in Java eliminates the error class entirely and removes fields from the LLM's output schema,
making the prompt simpler and the retry surface smaller.

## Trade-offs

None meaningful. The only scenario where LLM-owned counts would be preferable is if the derivation logic were
complex domain reasoning — which it is not.
