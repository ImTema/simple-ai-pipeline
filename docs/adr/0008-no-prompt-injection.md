# ADR-0008: No prompt-injection guard

**Status**: Accepted

## Context

Both pipeline services construct prompts entirely in the user turn — there is no system-role
message and no instructions telling the model to ignore injected content. This means a
malicious payload embedded in log snippets or provider documentation could, in principle,
redirect the model's output.

## Decision

No system prompt and no prompt-injection guard.

## Why

Both endpoints are internal tools intended for on-call and integration engineers, not exposed
to untrusted input from external users. The threat surface — log snippets pasted from ELK,
provider API docs copied from vendor pages — is controlled by the engineers using the service.

The output is read-only analysis JSON returned to the caller; the service executes no actions,
writes to no external systems, and holds no sensitive credentials. A successful injection
produces a misleading diagnosis, not a security breach.

## Trade-offs

**What we give up**: any deployment that widens the input surface (e.g. public web UI, webhook
ingestion from external systems) inherits injection risk without a guard in place.

**If that changes**: add a brief system-role message asserting the model's role and instructing
it to treat all user content as untrusted data, not instructions.
