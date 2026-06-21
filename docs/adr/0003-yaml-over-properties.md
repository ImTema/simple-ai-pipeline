# ADR-0003: YAML over .properties for configuration

**Status**: Accepted

## Context

Spring Boot supports both `.properties` and `.yml` for configuration. The project originally used
`application.properties` with flat key=value syntax.

## Decision

All configuration files use YAML (`application.yml`, `application-local.yml`).

## Why

AI agents exploring or editing the codebase read configuration files as raw text. YAML's hierarchical
structure lets the agent see that `spring.ai.openai.api-key` and `spring.ai.openai.base-url` belong
to the same namespace at a glance — without scanning repeated prefixes. Fewer tokens per file means
lower cost and less context consumed per agent turn.

## Trade-offs

**What we give up**: `.properties` is marginally simpler to hand-edit for single values.

**Not a concern**: Spring Boot treats both formats identically at runtime; no migration risk.
