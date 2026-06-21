package com.simplepipeline.loganalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepipeline.loganalyzer.model.IncidentAnalysis;
import com.simplepipeline.loganalyzer.model.IncidentSummary;
import com.simplepipeline.loganalyzer.model.LogSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LogAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(LogAnalyzerService.class);
    private static final int MAX_RETRIES = 4;

    private static final String LOG_SIGNALS_SCHEMA = JsonSchemaGenerator.generateForType(LogSignals.class);
    private static final String INCIDENT_ANALYSIS_SCHEMA = JsonSchemaGenerator.generateForType(IncidentAnalysis.class);

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
        log.info("[pipeline] starting log analysis, input_length={}", rawLogs.length());
        LogSignals signals = parse(rawLogs);
        String enrichedPrompt = enrich(signals, rawLogs);
        IncidentAnalysis diagnosis = diagnose(enrichedPrompt);
        IncidentAnalysis result = review(diagnosis, signals);
        log.info("[pipeline] analysis complete, incident_id={}", result.incidentId());
        return result;
    }

    // Stage 1: LLM extracts structured signals from raw log snippets
    private LogSignals parse(String rawLogs) {
        log.info("[stage:1:parse] extracting signals from logs");
        String prompt = """
                Extract structured signals from the following payment adapter log snippets.
                Return ONLY valid JSON matching this schema:
                """ + LOG_SIGNALS_SCHEMA + """

                Logs:
                """ + rawLogs;

        String correctionTemplate = """
                Your previous response was not valid JSON or was missing required fields.
                Error: %s
                Previous response: %s

                Return ONLY valid JSON matching this schema:
                """ + LOG_SIGNALS_SCHEMA;

        LogSignals result = callWithRetry(prompt, correctionTemplate, response ->
                objectMapper.readValue(extractJson(response), LogSignals.class));
        log.info("[stage:1:parse] done, components={}, error_types={}", result.affectedComponents().size(), result.errorTypes().size());
        return result;
    }

    // Stage 2: Java enriches extracted signals with system architecture context — no LLM call
    private String enrich(LogSignals signals, String rawLogs) {
        log.info("[stage:2:enrich] building enriched context, incident_id={}", signals.incidentId());
        String enriched = ARCHITECTURE_CONTEXT + """

                Extracted signals from the logs:
                  Incident ID        : """ + (signals.incidentId() != null ? signals.incidentId() : "not identified") + """

                  Error types        : """ + String.join(", ", signals.errorTypes()) + """

                  Affected components: """ + String.join(", ", signals.affectedComponents()) + """

                  Timeframe          : """ + (signals.timestamps().isEmpty() ? "unknown" : String.join(" → ", signals.timestamps())) + """


                Raw log snippets:
                """ + rawLogs;
        log.info("[stage:2:enrich] done");
        return enriched;
    }

    // Stage 3: LLM generates the full incident diagnosis from enriched context
    private IncidentAnalysis diagnose(String enrichedPrompt) {
        log.info("[stage:3:diagnose] generating incident diagnosis");
        String prompt = """
                You are a payment platform on-call expert. Diagnose this adapter incident.

                Rules:
                - category: one short phrase describing the failure class
                - hypotheses: up to 3 entries ranked by probability
                - immediate_actions: up to 2 entries

                Return ONLY valid JSON matching this schema:
                """ + INCIDENT_ANALYSIS_SCHEMA + """

                Context:
                """ + enrichedPrompt;

        String correctionTemplate = """
                Your previous response had validation errors: %s
                Previous response: %s

                Fix all issues and return ONLY valid JSON matching this schema:
                """ + INCIDENT_ANALYSIS_SCHEMA;

        IncidentAnalysis result = callWithRetry(prompt, correctionTemplate, response -> {
            IncidentAnalysis r = objectMapper.readValue(extractJson(response), IncidentAnalysis.class);
            validate(r);
            return r;
        });
        log.info("[stage:3:diagnose] done, category={}, hypotheses={}", result.category(), result.hypotheses().size());
        return result;
    }

    // Stage 4: Java reviews structural correctness and fills incidentId from signals if LLM left it null
    private IncidentAnalysis review(IncidentAnalysis analysis, LogSignals signals) {
        log.info("[stage:4:review] reviewing structural correctness");
        String resolvedId = analysis.incidentId() != null ? analysis.incidentId() : signals.incidentId();

        if (resolvedId == null || resolvedId.equals(analysis.incidentId())) {
            log.info("[stage:4:review] done, no id fix needed");
            return analysis;
        }

        log.info("[stage:4:review] done, patched incident_id={}", resolvedId);
        return new IncidentAnalysis(
                resolvedId,
                analysis.category(),
                analysis.summary(),
                analysis.hypotheses(),
                analysis.immediateActions()
        );
    }

    private void validate(IncidentAnalysis result) {
        var errors = new java.util.ArrayList<String>();
        IncidentSummary s = result.summary();

        if (s == null) errors.add("summary is null");

        if (result.hypotheses() == null || result.hypotheses().isEmpty())
            errors.add("hypotheses is empty");
        else {
            for (var h : result.hypotheses()) {
                if (h.nextSteps() == null || h.nextSteps().isEmpty())
                    errors.add("Hypothesis '" + h.title() + "' has no next_steps");
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
                log.debug("[llm] attempt={}", attempt + 1);
                lastResponse = chatClient.prompt(prompt).call().content();
                log.debug("[llm] lastResponse={}", lastResponse);
                return parser.parse(lastResponse);
            } catch (Exception e) {
                lastError = e;
                log.warn("[llm] attempt={} failed: {}", attempt + 1, e.getMessage());
                prompt = correctionTemplate.formatted(e.getMessage(), lastResponse);
            }
        }
        log.error("[llm] all {} attempts failed: {}", MAX_RETRIES, lastError.getMessage());
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
