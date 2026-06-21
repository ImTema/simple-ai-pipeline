package com.simplepipeline.loganalyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepipeline.loganalyzer.model.IncidentAnalysis;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("eval")
class LogAnalyzerEvalTest {

    record Expectation(String file, String faultLayer, String severity, String blastRadius) {}

    @Autowired
    LogAnalyzerService service;

    static Stream<Expectation> incidents() throws Exception {
        var mapper = new ObjectMapper();
        try (InputStream in = LogAnalyzerEvalTest.class.getResourceAsStream("/samples/expected/log-analyzer.json")) {
            List<Expectation> list = mapper.readValue(in,
                    mapper.getTypeFactory().constructCollectionType(List.class, Expectation.class));
            return list.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incidents")
    void classifiesIncident(Expectation e) throws Exception {
        String input;
        try (InputStream in = getClass().getResourceAsStream("/samples/" + e.file())) {
            input = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        IncidentAnalysis result = service.analyze(input);

        assertThat(result.summary().faultLayer().name())
                .as("%s faultLayer", e.file()).isEqualTo(e.faultLayer());
        assertThat(result.summary().severity().name())
                .as("%s severity", e.file()).isEqualTo(e.severity());
        assertThat(result.summary().blastRadius().name())
                .as("%s blastRadius", e.file()).isEqualTo(e.blastRadius());
    }
}
