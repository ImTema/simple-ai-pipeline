# Simple Pipeline

Minimal AI agent service for payment integration engineers. Two LLM-powered pipelines in a single Spring Boot app.

---

## Stack

- **Java 21** + Spring Boot + Spring AI
- **Docker** — runs the app and a local Ollama instance
- **Testcontainers** — spins up a pre-built Ollama image with `phi4-mini` for eval tests

---

## Prerequisites

Docker must be running. The app expects an Ollama instance with `phi4-mini` pulled, or any OpenAI-compatible endpoint via env vars:

```bash
export AI_BASE_URL=https://api.openai.com   # default: http://localhost:11434
export AI_API_KEY=your_key_here
export AI_MODEL=gpt-4o                      # default: phi4-mini
```

To run locally with Ollama:

```bash
docker run -d -p 11434:11434 ollama/ollama
docker exec -it <container> ollama pull phi4-mini
./mvnw spring-boot:run
```

---

## Endpoints

Both accept `text/plain`, return JSON, and block for **10–30 seconds** while the pipeline runs.

```bash
curl -X POST http://localhost:8083/decline-mapper/analyze -H "Content-Type: text/plain" --data-binary @docs.txt
curl -X POST http://localhost:8083/log-analyzer/analyze  -H "Content-Type: text/plain" --data-binary @logs.txt
```

---

## Pipeline structure

Each request runs a four-stage pipeline. Stages alternate between LLM and Java:

| Stage | Who | Decline Mapper | Log Analyzer |
|-------|-----|----------------|--------------|
| **parse** | LLM | Extract `(code, name, description)` tuples from raw docs | Extract signals: incident ID, error types, components, timestamps |
| **enrich** | Java | Inject taxonomy + retry strategy definitions | Inject 3-layer adapter architecture + infrastructure context |
| **map / diagnose** | LLM | Map each code to category, confidence, retry strategy | Generate full incident analysis: hypotheses, immediate actions |
| **assemble / review** | Java | Compute `needs_human_review` and summary counts | Validate enums; fill `incident_id` from signals if LLM left it null |

Each LLM stage retries up to 4 times with a targeted correction prompt before failing with HTTP 500.

---

## Docs

- `CONTEXT.md` — domain glossary and API contract
- `docs/adr/` — architecture decisions and recorded trade-offs

---

## Testing

Eval tests run the full pipeline against real sample inputs using a pre-built Ollama Docker image with `phi4-mini` — no API key needed, Docker must be running.

```bash
./mvnw test -Dgroups=eval
```

Sample inputs and expected fixtures are in `src/test/resources/samples/`. Tests assert on structured enum fields (`faultLayer`, `severity`, `blastRadius` for log analysis; category mappings for decline codes).
