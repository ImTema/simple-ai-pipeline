# Simple Pipeline — Domain Context

Minimal, standalone AI agent service for payment integration engineers.
Implements two features in a single Spring Boot application.

---

## Bounded Contexts

### 1. Decline Code Mapper

Maps a provider's raw error code documentation to our internal decline taxonomy.
Input: plain-text provider API docs. Output: structured mapping with confidence, retry strategy, and human-review flags.

**LLM responsibility**: parse raw text into structured codes; map each code to a taxonomy category.
**Java responsibility**: compute `needs_human_review` (true when confidence is `low`); compute all summary counts.

#### Key terms

| Term | Meaning |
|------|---------|
| **Provider Code** | A raw error code from a provider's API documentation (e.g. `QP-006`) |
| **Internal Category** | One of 7 fixed decline categories in our taxonomy |
| **Confidence** | LLM certainty in a mapping: `high`, `medium`, or `low` |
| **Needs Human Review** | Derived by Java: true whenever confidence is `low` — not delegated to the LLM |
| **Retry Strategy** | `no_retry`, `retry_with_backoff`, `retry_after_fix`, `no_action` |
| **Summary** | Aggregate counts computed by Java from the mappings array, never by the LLM |

#### Internal Decline Taxonomy (fixed, 7 categories)

| Category                 | Retryable |
|--------------------------|-----------|
| `SYSTEM_MALFUNCTION`     | Yes (with backoff) |
| `COMMON_DECLINE`         | Depends on sub-code |
| `ANTIFRAUD`              | No |
| `BAD_DATA_PROVIDED`      | No |
| `CANCELLED_BY_CUSTOMER`  | No |
| `PROVIDER_LIMIT`         | Yes (after cooldown) |
| `AUTHENTICATION_FAILURE` | No |

---

### 2. Log Analyzer

Diagnoses payment adapter incidents from raw log snippets.
Input: plain-text log snippets. Output: structured incident analysis with hypotheses and immediate actions.

**LLM responsibility**: extract signals from logs; generate diagnosis with hypotheses and immediate actions.
**Java responsibility**: validate all enum values; ensure tool names are from the known infrastructure set; fill incident_id from parsed signals if LLM left it null.

#### Key terms

| Term | Meaning |
|------|---------|
| **Log Signals** | Structured extraction of error types, affected components, and timestamps from raw logs |
| **Fault Layer** | `SDK`, `Core`, `API`, `Infrastructure`, or `External` |
| **Incident Category** | Short phrase classifying the failure (e.g. "External provider degradation") |
| **Hypothesis** | Candidate root cause with probability (`likely`, `possible`, `unlikely`) and next steps |
| **Blast Radius** | `single_merchant`, `single_adapter`, `multi_adapter`, or `platform_wide` |
| **Immediate Action** | Mitigation step with risk level: `safe`, `caution`, or `risky` |

---

## API

Both features use the same sync pattern — POST blocks until the full result is returned (10–30s depending on model).

| Endpoint | Input | Output |
|----------|-------|--------|
| `POST /decline-mapper/analyze` | `text/plain` — raw provider API docs | `MappingResult` JSON |
| `POST /log-analyzer/analyze` | `text/plain` — raw log snippets | `IncidentAnalysis` JSON |

---

## Architecture decisions

See `docs/adr/` for recorded decisions.
