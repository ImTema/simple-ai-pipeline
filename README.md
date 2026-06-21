# Simple Pipeline

Minimal AI agent service for payment integration engineers. Two features, one Spring Boot app, zero boilerplate.

---

## How to run

### Prerequisites

- Docker + Docker Compose, **or** Java 21 + Maven

### With Docker Compose

```bash
export AI_API_KEY=your_key_here
# Optional overrides:
# export AI_BASE_URL=https://api.openai.com   (any OpenAI-compatible endpoint)
# export AI_MODEL=gpt-4o

docker compose up --build
```

The service starts on port `8083`.

### Without Docker (local Maven)

```bash
export AI_API_KEY=your_key_here
./mvnw spring-boot:run
```

---

## Endpoints

Both endpoints accept `text/plain` and return JSON. Responses take **10–30 seconds** — the request blocks while the LLM pipeline runs.

### Decline Code Mapper

```bash
curl -X POST http://localhost:8083/decline-mapper/analyze \
  -H "Content-Type: text/plain" \
  --data-binary @your_provider_docs.txt
```

### Log Analyzer

```bash
curl -X POST http://localhost:8083/log-analyzer/analyze \
  -H "Content-Type: text/plain" \
  --data-binary @your_log_snippet.txt
```

---

## Agent structure

Each feature runs a four-stage pipeline inside its service class. Stages are private methods — no framework, no abstractions.

### Decline Code Mapper (`DeclineMapperService`)

| Stage | Who | What |
|-------|-----|------|
| **parse** | LLM | Extracts `(code, name, description)` tuples from raw documentation |
| **enrich** | Java | Builds enriched prompt with the full taxonomy and retry strategy definitions |
| **map** | LLM | Maps each code to a category, confidence level, retry strategy, and reasoning |
| **assemble** | Java | Computes `needs_human_review` (= confidence is `low`) and all summary counts |

The LLM never counts. `total_codes`, `high_confidence`, `needs_review`, `unmapped` are computed from the mappings array.

### Log Analyzer (`LogAnalyzerService`)

| Stage | Who | What |
|-------|-----|------|
| **parse** | LLM | Extracts signals: incident ID, error types, affected components, timestamps |
| **enrich** | Java | Injects the 3-layer adapter architecture and infrastructure tool context |
| **diagnose** | LLM | Generates the full incident analysis: category, summary, hypotheses, immediate actions |
| **review** | Java | Validates all enum values and tool names; fills `incident_id` from parsed signals if absent |

### Retry strategy

Each LLM stage retries up to **4 times** with a targeted correction prompt on failure (invalid JSON, wrong enum value, missing codes, hallucinated tool names). After 4 attempts the request fails with HTTP 500.

---

## Testing

Eval tests run the full LLM pipeline against sample inputs and assert on structured enum fields. Sample inputs and expected fixtures are in `src/test/resources/samples/`.

```bash
export AI_API_KEY=your_key_here
./mvnw test -Dgroups=eval
```

| Incident | Input file | Expected `faultLayer` | Expected `severity` | Expected `blastRadius` |
|----------|------------|----------------------|--------------------|-----------------------|
| INC-201 | `inc-201-opay.txt` | `EXTERNAL` | `HIGH` | `SINGLE_ADAPTER` |
| INC-202 | `inc-202-signature.txt` | `CORE` | `HIGH` | `SINGLE_ADAPTER` |
| INC-203 | `inc-203-halopesa.txt` | `INFRASTRUCTURE` | `HIGH` | `SINGLE_ADAPTER` |
| INC-204 | `inc-204-terminal-link.txt` | `INFRASTRUCTURE` | `MEDIUM` | `MULTI_ADAPTER` |
| INC-205 | `inc-205-pool.txt` | `SDK` | `HIGH` | `SINGLE_ADAPTER` |

The QuickPay Global provider docs (`quickpay-global.txt`) drive the `DeclineMapperEvalTest`, asserting 20 unambiguous code mappings. Ambiguous codes (QP-001, QP-005, QP-008, QP-009, QP-010, QP-401) are excluded from strict assertions.

---

## Trade-offs

### What was simplified

- **Sync over async** — the POST blocks until the pipeline finishes. An on-call engineer staring at a terminal
  for 20 seconds is fine; a production-grade system would need async with polling (see ADR-0001).
- **In-process only** — no persistence, no job queue. Results exist only in the HTTP response.
- **Single retry budget** — both services share the same 4-retry limit per stage. A smarter system would
  tune retry counts per stage based on observed failure rates.

### What production would add

- **Async + idempotency key** — submit returns a job ID, client polls for result. The idempotency key prevents
  re-processing the same log snippet or documentation if the client retries due to a network timeout.
- **Streaming progress** — SSE or WebSocket to show which stage is currently running (parse → enrich → diagnose).
- **Persistent results** — store completed analyses so engineers can reference past diagnoses.
- **Real tool integrations** — fetch live ELK queries or Grafana snapshots instead of suggesting them.
- **Historical incident correlation** — compare new signals against past incidents to surface known patterns.
