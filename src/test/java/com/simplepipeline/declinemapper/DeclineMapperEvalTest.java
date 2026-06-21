package com.simplepipeline.declinemapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepipeline.OllamaEvalBase;
import com.simplepipeline.declinemapper.model.CodeMapping;
import com.simplepipeline.declinemapper.model.MappingResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("eval")
class DeclineMapperEvalTest extends OllamaEvalBase {

    record Expectation(String providerCode, String internalCategory, String retryStrategy, Boolean needsHumanReview) {}

    @Autowired
    DeclineMapperService service;

    @Test
    void mapsQuickPayCodes() throws Exception {
        String input;
        try (InputStream in = getClass().getResourceAsStream("/samples/quickpay-global.txt")) {
            input = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        MappingResult result = service.analyze(input);

        Map<String, CodeMapping> byCode = result.mappings().stream()
                .collect(Collectors.toMap(CodeMapping::providerCode, m -> m));

        List<Expectation> expectations;
        var mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/samples/expected/decline-mapper.json")) {
            expectations = mapper.readValue(in,
                    mapper.getTypeFactory().constructCollectionType(List.class, Expectation.class));
        }

        for (Expectation e : expectations) {
            CodeMapping m = byCode.get(e.providerCode());
            assertThat(m).as("mapping present for %s", e.providerCode()).isNotNull();
            assertThat(m.internalCategory().name())
                    .as("%s internalCategory", e.providerCode()).isEqualTo(e.internalCategory());
            assertThat(m.retryStrategy().name())
                    .as("%s retryStrategy", e.providerCode()).isEqualTo(e.retryStrategy());
            if (Boolean.TRUE.equals(e.needsHumanReview())) {
                assertThat(m.needsHumanReview())
                        .as("%s needsHumanReview", e.providerCode()).isTrue();
            }
        }
    }
}
