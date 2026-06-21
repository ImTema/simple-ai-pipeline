package com.simplepipeline.declinemapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepipeline.declinemapper.model.CodeMapping;
import com.simplepipeline.declinemapper.model.Confidence;
import com.simplepipeline.declinemapper.model.InternalCategory;
import com.simplepipeline.declinemapper.model.MappingResult;
import com.simplepipeline.declinemapper.model.MappingSummary;
import com.simplepipeline.declinemapper.model.ProviderCode;
import com.simplepipeline.declinemapper.model.RetryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DeclineMapperService {

    private static final Logger log = LoggerFactory.getLogger(DeclineMapperService.class);
    private static final int MAX_RETRIES = 4;

    private static final String PROVIDER_CODE_SCHEMA = JsonSchemaGenerator.generateForType(ProviderCode[].class);
    private static final String CODE_MAPPING_SCHEMA = JsonSchemaGenerator.generateForType(CodeMapping[].class);

    private static final String TAXONOMY = buildTaxonomy();
    private static final String RETRY_STRATEGIES = buildRetryStrategies();

    private static String buildTaxonomy() {
        var sb = new StringBuilder();
        for (InternalCategory c : InternalCategory.values())
            sb.append(c.name()).append(" - ").append(c.description).append("\n");
        return sb.toString();
    }

    private static String buildRetryStrategies() {
        var sb = new StringBuilder();
        for (RetryStrategy s : RetryStrategy.values())
            sb.append(s.name()).append(" - ").append(s.description).append("\n");
        return sb.toString();
    }

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public DeclineMapperService(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    public MappingResult analyze(String rawDoc) {
        log.info("[pipeline] starting decline mapping, input_length={}", rawDoc.length());
        List<ProviderCode> codes = parse(rawDoc);
        String enrichedPrompt = enrich(codes);
        List<CodeMapping> rawMappings = map(enrichedPrompt, codes);
        MappingResult result = assemble(rawMappings, rawDoc, codes);
        log.info("[pipeline] mapping complete, provider={}, total_codes={}", result.provider(), result.summary().totalCodes());
        return result;
    }

    // Stage 1: LLM extracts structured (code, name, description) tuples from raw documentation
    private List<ProviderCode> parse(String rawDoc) {
        log.info("[stage:1:parse] extracting provider codes from documentation");
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

        List<ProviderCode> result = callWithRetry(prompt, correctionTemplate, response -> {
            List<ProviderCode> r = objectMapper.readValue(extractJson(response), new TypeReference<>() {});
            if (r.isEmpty()) throw new IllegalStateException("No codes extracted");
            return r;
        });
        log.info("[stage:1:parse] done, codes_extracted={}", result.size());
        return result;
    }

    // Stage 2: Java enriches the extracted codes with taxonomy context — no LLM call
    private String enrich(List<ProviderCode> codes) {
        log.info("[stage:2:enrich] building taxonomy context for {} codes", codes.size());
        StringBuilder sb = new StringBuilder();
        sb.append("Internal decline taxonomy (map each provider code to exactly one of these):\n");
        sb.append(TAXONOMY);
        sb.append("\nRetry strategies:\n");
        sb.append(RETRY_STRATEGIES);
        sb.append("\nProvider error codes to map:\n");
        for (ProviderCode code : codes) {
            sb.append(String.format("  %s \"%s\" - %s%n", code.code(), code.name(), code.description()));
        }
        log.info("[stage:2:enrich] done");
        return sb.toString();
    }

    // Stage 3: LLM maps each code to a category, confidence, retry strategy, and reasoning
    private List<CodeMapping> map(String enrichedPrompt, List<ProviderCode> originalCodes) {
        log.info("[stage:3:map] mapping {} codes to internal taxonomy", originalCodes.size());
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

        List<CodeMapping> mappings = callWithRetry(prompt, correctionTemplate, response -> {
            List<CodeMapping> result = objectMapper.readValue(extractJson(response), new TypeReference<>() {});

            var mappedCodes = new HashSet<String>();
            var errors = new java.util.ArrayList<String>();
            for (CodeMapping m : result) mappedCodes.add(m.providerCode());
            for (String expected : expectedCodes) {
                if (!mappedCodes.contains(expected)) errors.add("Missing code: " + expected);
            }
            if (!errors.isEmpty()) throw new IllegalStateException(String.join("; ", errors));
            return result;
        });
        log.info("[stage:3:map] done, mappings={}", mappings.size());
        return mappings;
    }

    // Stage 4: Java assembles the final result — computes needs_human_review and summary counts
    private MappingResult assemble(List<CodeMapping> rawMappings, String rawDoc, List<ProviderCode> codes) {
        log.info("[stage:4:assemble] computing final result");
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

        log.info("[stage:4:assemble] done, high_confidence={}, needs_review={}", highConfidence, needsReview);
        return new MappingResult(provider, version, finalMappings,
                new MappingSummary(totalCodes, highConfidence, needsReview, unmapped));
    }

    private <T> T callWithRetry(String initialPrompt, String correctionTemplate, LlmParser<T> parser) {
        String prompt = initialPrompt;
        String lastResponse = "";
        Exception lastError = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                log.debug("[llm] attempt={}", attempt + 1);
                lastResponse = chatClient.prompt(prompt).call().content();
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
