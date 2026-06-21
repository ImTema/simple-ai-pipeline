# ADR-0007: No LLM request batching

**Status**: Accepted

## Context

Under concurrent load, multiple pipeline runs will issue LLM calls for the same stage type simultaneously
(e.g. 10 engineers submitting log analysis at once). LLM providers support batching — multiple inputs in a
single request — which reduces latency and can lower cost.

## Decision

Each pipeline run issues its LLM calls individually. No batching infrastructure.

## Why

The current sync, single-user prototype has no concurrent load. Batching requires a job queue, a flush
trigger (time window, size window, or hybrid), and a fan-out mechanism to return each result to its originating
request. That infrastructure only pays off when concurrent requests are the norm.

## Trade-offs

**What we give up**: throughput gains under concurrent load; potential cost reduction from fewer API round-trips.

**Production path**: with the async/persistence model in place (see ADR-0005), batch by stage type — collect
all pending `parse` jobs together, all `diagnose` jobs together. Use a hybrid flush trigger (e.g. every 5 seconds
or 10 jobs, whichever comes first) to avoid starving solo requests during low traffic.
