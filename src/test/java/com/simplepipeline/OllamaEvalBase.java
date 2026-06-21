package com.simplepipeline;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.ollama.OllamaContainer;

import java.io.IOException;

@SpringBootTest
public abstract class OllamaEvalBase {

    private static final String MODEL = "phi4-mini";

    static final OllamaContainer OLLAMA = new OllamaContainer("ollama/ollama:latest").withReuse(true);

    static {
        OLLAMA.start();
        try {
            var result = OLLAMA.execInContainer("ollama", "pull", MODEL);
            if (result.getExitCode() != 0)
                throw new RuntimeException("ollama pull failed: " + result.getStderr());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", OLLAMA::getEndpoint);
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.chat.options.model", () -> MODEL);
    }
}
