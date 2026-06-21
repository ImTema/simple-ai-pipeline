package com.simplepipeline.declinemapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepipeline.declinemapper.model.CodeMapping;
import com.simplepipeline.declinemapper.model.MappingResult;
import com.simplepipeline.declinemapper.model.MappingSummary;
import com.simplepipeline.declinemapper.model.ProviderCode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DeclineMapperService {

    private static final int MAX_RETRIES = 4;

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "SYSTEM_MALFUNCTION", "COMMON_DECLINE", "ANTIFRAUD",
            "BADDATAPROVIDED", "CANCELLEDBYCUSTOMER", "PROVIDER_LIMIT", "AUTHENTICATION_FAILURE"
    );
    private static final Set<String> VALID_RETRY_STRATEGIES = Set.of(
            "no_retry", "retry_with_backoff", "retry_after_fix", "no_action"
    );
    private static final Set<String> VALID_CONFIDENCE = Set.of("high", "medium", "low");

    private static final String TAXONOMY = """
            SYSTEM_MALFUNCTION     - Internal system error, provider infrastructure failure. Retryable with backoff.
            COMMON_DECLINE         - Generic bank/provider decline (insufficient funds, limit, expired card). Retryable depends on sub-code.
            ANTIFRAUD              - Transaction blocked by fraud detection. Not retryable.
            BADDATAPROVIDED        - Invalid input data (wrong account, invalid currency, malformed fields). Not retryable.
            CANCELLEDBYCUSTOMER    - Customer cancelled or abandoned the transaction. Not retryable.
            PROVIDER_LIMIT         - Provider rate limit or quota reached. Retryable after cooldown.
            AUTHENTICATION_FAILURE - Invalid credentials, expired token, signature mismatch. Not retryable.
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DeclineMapperService(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    public MappingResult analyze(String rawDoc) {
        List<ProviderCode> codes = parse(rawDoc);
        String enrichedPrompt = enrich(codes);
        List<CodeMapping> rawMappings = map(enrichedPrompt, codes);
        return assemble(rawMappings, rawDoc, codes);
    }

    // Stage 1: LLM extracts structured (code, name, description) tuples from raw documentation
    private List<ProviderCode> parse(String rawDoc) {
        String prompt = """
                Extract all error/decline codes from the following provider API documentation.
                Return ONLY a valid JSON array. Each element must have exactly these fields:
                  "code": the code string (e.g. "QP-001")
                  "name": the short name or title
                  "description": the explanation text

                Documentation:
                """ + rawDoc;

        String correctionTemplate = """
                Your previous response was not valid JSON or had wrong structure.
                Error: %s
                Previous response: %s

                Return ONLY a valid JSON array of objects with fields: code, name, description.
                """;

        return callWithRetry(prompt, correctionTemplate, response -> {
            List<ProviderCode> result = objectMapper.readValue(extractJson(response), new TypeReference<>() {});
            if (result.isEmpty()) throw new IllegalStateException("No codes extracted");
            return result;
        });
    }

    // Stage 2: Java enriches the extracted codes with taxonomy context — no LLM call
    private String enrich(List<ProviderCode> codes) {
        StringBuilder sb = new StringBuilder();
        sb.append("Internal decline taxonomy (map each provider code to exactly one of these):\n");
        sb.append(TAXONOMY);
        sb.append("\nRetry strategies:\n");
        sb.append("  no_retry           - terminal error, do not retry\n");
        sb.append("  retry_with_backoff - transient error, retry with exponential backoff\n");
        sb.append("  retry_after_fix    - retryable only after merchant/customer fixes something\n");
        sb.append("  no_action          - not a real error (e.g. idempotency guard)\n");
        sb.append("\nProvider error codes to map:\n");
        for (ProviderCode code : codes) {
            sb.append(String.format("  %s \"%s\" - %s%n", code.code(), code.name(), code.description()));
        }
        return sb.toString();
    }

    // Stage 3: LLM maps each code to a category, confidence, retry strategy, and reasoning
    private List<CodeMapping> map(String enrichedPrompt, List<ProviderCode> originalCodes) {
        Set<String> expectedCodes = new java.util.HashSet<>();
        for (ProviderCode c : originalCodes) expectedCodes.add(c.code());

        String prompt = """
                You are a payment integration expert. Map each provider error code to our internal decline taxonomy.

                Rules:
                - internal_category must be exactly one of the 7 values in the taxonomy
                - confidence must be "high", "medium", or "low"
                - retry_strategy must be exactly one of the 4 values listed
                - review_reason must explain ambiguity if confidence is "low", otherwise null
                - Every code in the list must appear in the output

                Return ONLY a valid JSON array. Each element must have exactly these fields:
                  "provider_code", "provider_message", "internal_category", "confidence",
                  "reasoning", "retry_strategy", "review_reason"

                """ + enrichedPrompt;

        String correctionTemplate = """
                Your previous response had validation errors: %s
                Previous response: %s

                Fix all issues and return ONLY a valid JSON array with the same fields.
                Every provider code must appear. Use only the allowed enum values.
                """;

        return callWithRetry(prompt, correctionTemplate, response -> {
            List<CodeMapping> mappings = objectMapper.readValue(extractJson(response), new TypeReference<>() {});

            List<String> errors = new java.util.ArrayList<>();
            Set<String> mappedCodes = new java.util.HashSet<>();
            for (CodeMapping m : mappings) {
                mappedCodes.add(m.providerCode());
                if (!VALID_CATEGORIES.contains(m.internalCategory()))
                    errors.add("Invalid category '" + m.internalCategory() + "' for code " + m.providerCode());
                if (!VALID_RETRY_STRATEGIES.contains(m.retryStrategy()))
                    errors.add("Invalid retry strategy '" + m.retryStrategy() + "' for code " + m.providerCode());
                if (!VALID_CONFIDENCE.contains(m.confidence()))
                    errors.add("Invalid confidence '" + m.confidence() + "' for code " + m.providerCode());
            }
            for (String expected : expectedCodes) {
                if (!mappedCodes.contains(expected))
                    errors.add("Missing code: " + expected);
            }
            if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
            return mappings;
        });
    }

    // Stage 4: Java assembles the final result — computes needs_human_review and summary counts
    private MappingResult assemble(List<CodeMapping> rawMappings, String rawDoc, List<ProviderCode> codes) {
        String provider = extractProviderName(rawDoc);
        String version = extractVersion(rawDoc);

        List<CodeMapping> finalMappings = rawMappings.stream()
                .map(m -> new CodeMapping(
                        m.providerCode(),
                        m.providerMessage(),
                        m.internalCategory(),
                        m.confidence(),
                        m.reasoning(),
                        m.retryStrategy(),
                        m.needsHumanReview() || "low".equals(m.confidence()),
                        m.reviewReason()
                ))
                .toList();

        int totalCodes = codes.size();
        int highConfidence = (int) finalMappings.stream().filter(m -> "high".equals(m.confidence())).count();
        int needsReview = (int) finalMappings.stream().filter(CodeMapping::needsHumanReview).count();
        int unmapped = totalCodes - finalMappings.size();

        return new MappingResult(provider, version, finalMappings,
                new MappingSummary(totalCodes, highConfidence, needsReview, unmapped));
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
        // Strip markdown code fences if present
        Pattern fence = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
        Matcher matcher = fence.matcher(trimmed);
        if (matcher.find()) return matcher.group(1).strip();
        // Find first [ or { to handle any leading text
        int start = Math.min(
                trimmed.indexOf('[') == -1 ? Integer.MAX_VALUE : trimmed.indexOf('['),
                trimmed.indexOf('{') == -1 ? Integer.MAX_VALUE : trimmed.indexOf('{')
        );
        return start == Integer.MAX_VALUE ? trimmed : trimmed.substring(start);
    }

    private String extractProviderName(String rawDoc) {
        Pattern p = Pattern.compile("(?i)provider[:\\s]+([A-Za-z0-9 ]+)|^([A-Za-z0-9 ]+)\\s*[-—]");
        Matcher m = p.matcher(rawDoc);
        return m.find() ? (m.group(1) != null ? m.group(1).strip() : m.group(2).strip()) : "Unknown Provider";
    }

    private String extractVersion(String rawDoc) {
        Pattern p = Pattern.compile("(?i)v(\\d+\\.\\d+(?:\\.\\d+)?)");
        Matcher m = p.matcher(rawDoc);
        return m.find() ? m.group(1) : "unknown";
    }

    @FunctionalInterface
    private interface LlmParser<T> {
        T parse(String response) throws Exception;
    }
}
