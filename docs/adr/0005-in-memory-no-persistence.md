# ADR-0005: In-memory processing, no persistence layer

**Status**: Accepted

## Context

Each pipeline run (DeclineMapper or LogAnalyzer) executes multiple LLM stages sequentially and returns a single
result. Persisting intermediate or final results requires a database, a repository abstraction, and a schema.

## Decision

No persistence. All pipeline state lives in the request thread and is discarded when the HTTP response is sent.

## Why

This is a prototype tool used by a small, known set of engineers. Result history, auditability, and job recovery
are not required at this stage.

## Trade-offs

**What we give up**: no auditability of intermediate stage outputs; no ability to resume a failed pipeline from
the last successful stage; no deduplication guard against re-processing the same input.

**Production path**: introduce a stage-per-row persistence model. Each LLM stage (`parse`, `map`/`diagnose`)
gets its own record with a `PENDING → IN_PROGRESS → COMPLETED | FAILED` lifecycle and its intermediate output
stored as JSON. The parent pipeline run is `COMPLETED` only when all stage records are `COMPLETED`. On failure,
the pipeline resumes from the first non-`COMPLETED` stage, reusing stored outputs from successful stages rather
than re-running them — avoiding wasted LLM tokens and keeping the audit trail clean.
