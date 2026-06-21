# ADR-0001: Synchronous request handling

**Status**: Accepted

## Context

LLM calls take 10–30 seconds. The existing `ai-orchestrator` handles this with an async submit-and-poll pattern
(POST returns an `analysisId`, client polls GET until `COMPLETED`). That pattern requires in-memory or persistent
state, a repository abstraction, and a separate async executor.

## Decision

This module handles requests synchronously. The POST blocks and returns the full result when the LLM pipeline
finishes. No polling, no job IDs, no async infrastructure.

## Why

This is an internal tool for on-call engineers who are already staring at the terminal waiting for a diagnosis.
A 20-second blocking call is acceptable in that context. The async pattern's main benefit — letting the client
do other work while waiting — is not relevant here.

## Trade-offs

**What we give up**: clients cannot query status mid-flight; a slow or hung LLM call holds the HTTP connection
open; no idempotency guard against duplicate submissions.

**Production path**: switch to async with an idempotency key on the submission endpoint. The key prevents
re-processing the same log snippet or documentation if the client retries due to a timeout. The sync service
method stays unchanged — the async wrapper just calls it in a background thread and stores the result.
