# ADR-0004: Schema-driven LLM prompts via @ToolParam + JsonSchemaGenerator

**Status**: Accepted

## Context

Both pipeline services embed JSON structure descriptions directly in prompt strings:

```java
"Return ONLY valid JSON matching this exact structure:\n{\n  \"fault_layer\": \"SDK|Core|API|...\"\n  ..."
```

When a model field is renamed or a new field is added, the prompt must be updated manually.
There is no compile-time link between the Java record and the prompt text. Drift between the
two is silent — the LLM receives stale instructions and may produce output that fails validation.

Additionally, enum-constrained fields (`fault_layer`, `severity`, `confidence`, etc.) are
typed as `String` and validated at runtime against static `Set<String>` guards. The allowed
values exist in three places: the Java sets, the prompt prose, and the LLM's implicit knowledge
— with no single source of truth.

## Decision

1. **Annotate all model record components with `@ToolParam(description = "...")`** from
   `org.springframework.ai.tool.annotation`. This captures field intent alongside the field
   declaration.

2. **Generate JSON schema at service construction time** using
   `org.springframework.ai.util.json.schema.JsonSchemaGenerator.generateForType(Class<?>)`.
   For single-object outputs pass the record class; for array outputs pass the array type
   (e.g. `CodeMapping[].class`) so the schema emits `{"type": "array", "items": {...}}`.

3. **Inject the generated schema into every LLM-calling stage** (all four: both `parse()`
   stages, `map()`, and `diagnose()`), replacing the hardcoded JSON structure prose.

4. **Convert all enum-constrained String fields to Java enums** with `UPPER_SNAKE_CASE`
   constants. `JsonSchemaGenerator` emits `"enum": [...]` for Java enum types automatically,
   so the allowed values appear in the schema the LLM receives — not in a separate validation
   set. The runtime `Set<String>` guards are deleted.

## Why

The model class becomes the single source of truth for both the Java type system and the LLM
prompt. A field change propagates to the prompt automatically. The LLM receives an explicit,
machine-generated schema — less ambiguity means fewer retries and more robust output.

Java enums eliminate the three-way duplication (Set guard + prompt prose + LLM knowledge)
and give compile-time safety on the Java side.

## Trade-offs

**What we give up**: model records now carry `@ToolParam` annotations from a Spring AI
internal package. If Spring AI's schema utility changes its annotation contract, all annotated
models need updating.

**Why not Spring AI structured output (`BeanOutputConverter` / `.entity(Class<?>)`)**: that
approach delegates schema injection and JSON parsing entirely to the framework and requires
model support for structured output mode. Our retry-with-correction loop is intentional
(ADR-0001 context: robustness over convenience) and must stay visible. Schema injection keeps
the prompt construction explicit and the retry logic under our control.

**Why not `@Schema` (Swagger/OpenAPI)**: adds an unrelated dependency (`springdoc` or
`swagger-annotations`) for a concern that Spring AI already covers.

**Enum case**: UPPER_SNAKE_CASE constants mean the LLM will see and produce uppercase values
(`"HIGH"`, `"RETRY_WITH_BACKOFF"`). This is a deliberate break from the previous mixed-case
wire format (`"high"`, `"retry_with_backoff"`). Jackson deserializes by enum name by default —
no `@JsonValue` needed.
