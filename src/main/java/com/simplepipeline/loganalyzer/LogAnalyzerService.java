package com.simplepipeline.loganalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepipeline.loganalyzer.model.IncidentAnalysis;
import com.simplepipeline.loganalyzer.model.IncidentSummary;
import com.simplepipeline.loganalyzer.model.LogSignals;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LogAnalyzerService {

    private static final int MAX_RETRIES = 4;

    private static final Set<String> VALID_FAULT_LAYERS = Set.of("SDK", "Core", "API", "Infrastructure", "External");
    private static final Set<String> VALID_SEVERITIES = Set.of("low", "medium", "high", "critical");
    private static final Set<String> VALID_BLAST_RADII = Set.of("single_merchant", "single_adapter", "multi_adapter", "platform_wide");
    private static final Set<String> VALID_PROBABILITIES = Set.of("likely", "possible", "unlikely");
    private static final Set<String> VALID_RISKS = Set.of("safe", "caution", "risky");
    private static final Set<String> KNOWN_TOOLS = Set.of("ELK", "Grafana", "Consul", "Vault", "Redis", "RabbitMQ", "PostgreSQL");

    private static final String ARCHITECTURE_CONTEXT = """
            Adapter architecture (3 layers):
              API Layer         - REST controllers, RabbitMQ consumers. Receives orders from the orchestrator (MI).
              Core Layer        - Business logic: status mapping, decline mapping, timeout handling, retry decisions.
              SDK Layer         - HTTP client to external provider, DTOs, RestClientProvider for HTTP with metrics.

            Supporting infrastructure:
              ELK               - Centralized log aggregation
              Grafana           - Metrics and alerting (Prometheus)
              Consul            - Service discovery
              Vault             - Stores API keys, signing keys, adapter credentials
              Redis             - Caches terminal link lookups (10-min TTL)
              RabbitMQ          - Async messaging for callbacks and status updates
              PostgreSQL        - Order persistence (separate instances per adapter)

            Common failure modes:
              - External provider degradation (timeouts, 5xx, maintenance windows)
              - Credential rotation issues (Vault secret updated but adapter not restarted)
              - Connection pool exhaustion under traffic spikes
              - Redis cache containing stale terminal links after configuration change
              - Signing key mismatch (X-Sign validation failures) after library upgrade
              - RabbitMQ queue backlog causing delayed callback processing
              - Database connection pool saturation from long-running queries
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public LogAnalyzerService(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    public IncidentAnalysis analyze(String rawLogs) {
        LogSignals signals = parse(rawLogs);
        String enrichedPrompt = enrich(signals, rawLogs);
        IncidentAnalysis diagnosis = diagnose(enrichedPrompt);
        return review(diagnosis, signals);
    }

    // Stage 1: LLM extracts structured signals from raw log snippets
    private LogSignals parse(String rawLogs) {
        String prompt = """
                Extract structured signals from the following payment adapter log snippets.
                Return ONLY valid JSON with exactly these fields:
                  "incident_id": the incident identifier if visible in the logs (e.g. "INC-201"), or null
                  "error_types": array of distinct error type strings found in the logs
                  "affected_components": array of component names (adapters, services, classes) mentioned
                  "timestamps": array of the first and last timestamps found

                Logs:
                """ + rawLogs;

        String correctionTemplate = """
                Your previous response was not valid JSON or was missing required fields.
                Error: %s
                Previous response: %s

                Return ONLY valid JSON with fields: incident_id, error_types, affected_components, timestamps.
                """;

        return callWithRetry(prompt, correctionTemplate, response ->
                objectMapper.readValue(extractJson(response), LogSignals.class));
    }

    // Stage 2: Java enriches extracted signals with system architecture context — no LLM call
    private String enrich(LogSignals signals, String rawLogs) {
        return ARCHITECTURE_CONTEXT + """

                Extracted signals from the logs:
                  Incident ID        : """ + (signals.incidentId() != null ? signals.incidentId() : "not identified") + """

                  Error types        : """ + String.join(", ", signals.errorTypes()) + """

                  Affected components: """ + String.join(", ", signals.affectedComponents()) + """

                  Timeframe          : """ + (signals.timestamps().isEmpty() ? "unknown" : String.join(" → ", signals.timestamps())) + """


                Raw log snippets:
                """ + rawLogs;
    }

    // Stage 3: LLM generates the full incident diagnosis from enriched context
    private IncidentAnalysis diagnose(String enrichedPrompt) {
        String prompt = """
                You are a payment platform on-call expert. Diagnose this adapter incident.

                Rules:
                - category: one short phrase describing the failure class
                - summary.fault_layer must be exactly one of: SDK, Core, API, Infrastructure, External
                - summary.severity must be exactly one of: low, medium, high, critical
                - summary.blast_radius must be exactly one of: single_merchant, single_adapter, multi_adapter, platform_wide
                - hypotheses: up to 3 entries, each with probability exactly one of: likely, possible, unlikely
                - each hypothesis must have 2-3 next_steps referencing real tools: ELK, Grafana, Consul, Vault, Redis, RabbitMQ, PostgreSQL
                - immediate_actions: up to 2 entries, each with risk exactly one of: safe, caution, risky

                Return ONLY valid JSON matching this exact structure:
                {
                  "incident_id": "string or null",
                  "category": "string",
                  "summary": {
                    "description": "string",
                    "affected_adapters": ["string"],
                    "affected_order_types": ["string"],
                    "fault_layer": "SDK|Core|API|Infrastructure|External",
                    "severity": "low|medium|high|critical",
                    "severity_reasoning": "string",
                    "blast_radius": "single_merchant|single_adapter|multi_adapter|platform_wide"
                  },
                  "hypotheses": [
                    {
                      "title": "string",
                      "reasoning": "string",
                      "probability": "likely|possible|unlikely",
                      "next_steps": [{"action": "string", "tool": "string", "detail": "string"}]
                    }
                  ],
                  "immediate_actions": [
                    {"action": "string", "risk": "safe|caution|risky", "reasoning": "string"}
                  ]
                }

                Context:
                """ + enrichedPrompt;

        String correctionTemplate = """
                Your previous response had validation errors: %s
                Previous response: %s

                Fix all issues and return ONLY valid JSON with the same structure.
                Use only the allowed enum values. Tool names must be from: ELK, Grafana, Consul, Vault, Redis, RabbitMQ, PostgreSQL.
                """;

        return callWithRetry(prompt, correctionTemplate, response -> {
            IncidentAnalysis result = objectMapper.readValue(extractJson(response), IncidentAnalysis.class);
            validate(result);
            return result;
        });
    }

    // Stage 4: Java reviews enum correctness and assembles final result
    // If validation fails the retry loop in diagnose() re-runs with a correction prompt.
    // Here we also fill in incidentId from parsed signals if the LLM left it null.
    private IncidentAnalysis review(IncidentAnalysis analysis, LogSignals signals) {
        String resolvedId = analysis.incidentId() != null ? analysis.incidentId() : signals.incidentId();

        if (resolvedId == null || resolvedId.equals(analysis.incidentId())) return analysis;

        return new IncidentAnalysis(
                resolvedId,
                analysis.category(),
                analysis.summary(),
                analysis.hypotheses(),
                analysis.immediateActions()
        );
    }

    private void validate(IncidentAnalysis result) {
        List<String> errors = new java.util.ArrayList<>();
        IncidentSummary s = result.summary();

        if (s == null) { errors.add("summary is null"); }
        else {
            if (!VALID_FAULT_LAYERS.contains(s.faultLayer()))
                errors.add("Invalid fault_layer: " + s.faultLayer());
            if (!VALID_SEVERITIES.contains(s.severity()))
                errors.add("Invalid severity: " + s.severity());
            if (!VALID_BLAST_RADII.contains(s.blastRadius()))
                errors.add("Invalid blast_radius: " + s.blastRadius());
        }

        if (result.hypotheses() == null || result.hypotheses().isEmpty())
            errors.add("hypotheses is empty");
        else {
            for (var h : result.hypotheses()) {
                if (!VALID_PROBABILITIES.contains(h.probability()))
                    errors.add("Invalid probability: " + h.probability());
                if (h.nextSteps() == null || h.nextSteps().isEmpty())
                    errors.add("Hypothesis '" + h.title() + "' has no next_steps");
                else {
                    for (var step : h.nextSteps()) {
                        if (!KNOWN_TOOLS.contains(step.tool()))
                            errors.add("Unknown tool '" + step.tool() + "' in hypothesis '" + h.title() + "'");
                    }
                }
            }
        }

        if (result.immediateActions() != null) {
            for (var action : result.immediateActions()) {
                if (!VALID_RISKS.contains(action.risk()))
                    errors.add("Invalid risk: " + action.risk());
            }
        }

        if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
    }

    private <T> T callWithRetry(String initialPrompt, String correctionTemplate, LlmParser<T> parser) {
        String prompt = initialPrompt;
        String lastResponse = "";
        Exception lastError = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                lastResponse = chatClient.prompt(prompt).call().content();
                return parser.parse(lastResponse);
            } catch (Exception e) {
                lastError = e;
                prompt = correctionTemplate.formatted(e.getMessage(), lastResponse);
            }
        }
        throw new RuntimeException("Analysis failed after " + MAX_RETRIES + " attempts: " + lastError.getMessage());
    }

    private String extractJson(String response) {
        String trimmed = response.strip();
        Pattern fence = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
        Matcher matcher = fence.matcher(trimmed);
        if (matcher.find()) return matcher.group(1).strip();
        int arrayStart = trimmed.indexOf('[');
        int objectStart = trimmed.indexOf('{');
        int start = (arrayStart == -1) ? objectStart
                : (objectStart == -1) ? arrayStart
                : Math.min(arrayStart, objectStart);
        return start == -1 ? trimmed : trimmed.substring(start);
    }

    @FunctionalInterface
    private interface LlmParser<T> {
        T parse(String response) throws Exception;
    }
}
