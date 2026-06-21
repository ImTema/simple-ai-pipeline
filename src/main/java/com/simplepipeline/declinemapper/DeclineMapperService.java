package com.simplepipeline.declinemapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepipeline.declinemapper.model.CodeMapping;
import com.simplepipeline.declinemapper.model.Confidence;
import com.simplepipeline.declinemapper.model.MappingResult;
import com.simplepipeline.declinemapper.model.MappingSummary;
import com.simplepipeline.declinemapper.model.ProviderCode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DeclineMapperService {

    private static final int MAX_RETRIES = 4;

    private static final String PROVIDER_CODE_SCHEMA = JsonSchemaGenerator.generateForType(ProviderCode[].class);
    private static final String CODE_MAPPING_SCHEMA = JsonSchemaGenerator.generateForType(CodeMapping[].class);

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
                Return ONLY a valid JSON array matching this schema:
                """ + PROVIDER_CODE_SCHEMA + """

                Documentation:
                """ + rawDoc;

        String correctionTemplate = """
                Your previous response was not valid JSON or had wrong structure.
                Error: %s
                Previous response: %s

                Return ONLY a valid JSON array matching this schema:
                """ + PROVIDER_CODE_SCHEMA;

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
        sb.append("  NO_RETRY           - terminal error, do not retry\n");
        sb.append("  RETRY_WITH_BACKOFF - transient error, retry with exponential backoff\n");
        sb.append("  RETRY_AFTER_FIX    - retryable only after merchant/customer fixes something\n");
        sb.append("  NO_ACTION          - not a real error (e.g. idempotency guard)\n");
        sb.append("\nProvider error codes to map:\n");
        for (ProviderCode code : codes) {
            sb.append(String.format("  %s \"%s\" - %s%n", code.code(), code.name(), code.description()));
        }
        return sb.toString();
    }

    // Stage 3: LLM maps each code to a category, confidence, retry strategy, and reasoning
    private List<CodeMapping> map(String enrichedPrompt, List<ProviderCode> originalCodes) {
        var expectedCodes = new HashSet<String>();
        for (ProviderCode c : originalCodes) expectedCodes.add(c.code());

        String prompt = """
                You are a payment integration expert. Map each provider error code to our internal decline taxonomy.

                Rules:
                - review_reason must explain ambiguity if confidence is LOW, otherwise null
                - Every code in the list must appear in the output

                Return ONLY a valid JSON array matching this schema:
                """ + CODE_MAPPING_SCHEMA + """

                """ + enrichedPrompt;

        String correctionTemplate = """
                Your previous response had validation errors: %s
                Previous response: %s

                Fix all issues and return ONLY a valid JSON array matching this schema:
                """ + CODE_MAPPING_SCHEMA;

        return callWithRetry(prompt, correctionTemplate, response -> {
            List<CodeMapping> mappings = objectMapper.readValue(extractJson(response), new TypeReference<>() {});

            var mappedCodes = new HashSet<String>();
            var errors = new java.util.ArrayList<String>();
            for (CodeMapping m : mappings) mappedCodes.add(m.providerCode());
            for (String expected : expectedCodes) {
                if (!mappedCodes.contains(expected)) errors.add("Missing code: " + expected);
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
                        m.needsHumanReview() || m.confidence() == Confidence.LOW,
                        m.reviewReason()
                ))
                .toList();

        int totalCodes = codes.size();
        int highConfidence = (int) finalMappings.stream().filter(m -> m.confidence() == Confidence.HIGH).count();
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
        Pattern fence = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
        Matcher matcher = fence.matcher(trimmed);
        if (matcher.find()) return matcher.group(1).strip();
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
