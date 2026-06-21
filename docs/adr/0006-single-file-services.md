# ADR-0006: Single-class services with inline stage methods

**Status**: Accepted

## Context

Each pipeline has 4 stages (2 LLM, 2 Java). Separating them into distinct classes per stage would follow
single-responsibility cleanly, with a shared abstract base extracting common infrastructure (`callWithRetry`,
`extractJson`). That structure also maps naturally to the async/persistence model where each stage runs as an
independent job.

## Decision

Each pipeline is a single `@Service` class. Stage logic lives as private methods. Shared utilities
(`callWithRetry`, `extractJson`) are duplicated between the two services.

## Why

For a two-feature prototype, one class per service is the fastest path to a working, readable implementation.
The stage boundaries are already documented via method names and log markers (`[stage:1:parse]`, `[stage:3:map]`).

## Trade-offs

**What we give up**: SOLID single-responsibility at the class level; shared utility code is duplicated; adding a
new stage or swapping an LLM call requires touching a large class.

**Production path**: extract a shared abstract base class with `callWithRetry` and `extractJson`. Split each
stage into its own class (`DeclineMapperParser`, `DeclineMapperMapper`, etc.) with the service becoming a thin
orchestrator. This split also aligns with the async job model — each stage class becomes the handler for its
corresponding job type.
