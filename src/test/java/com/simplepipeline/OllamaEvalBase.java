package com.simplepipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@TestPropertySource(locations = "classpath:eval-tests.yml")
public abstract class OllamaEvalBase {

    private static final Logger logger = LoggerFactory.getLogger(OllamaEvalBase.class);

    private static final String MODEL = "phi4-mini";
    // image name encodes the model so re-pulling is skipped on subsequent runs
    private static final String BAKED_IMAGE = "tc-ollama-" + MODEL + ":1";

    static final OllamaContainer OLLAMA;

    static {
        OllamaContainer c;
        var log = new Slf4jLogConsumer(LoggerFactory.getLogger("ollama"));
        try {
            logger.info("Starting OLLAMA baked image: " + BAKED_IMAGE);
            var bakedImage = DockerImageName.parse(BAKED_IMAGE).asCompatibleSubstituteFor("ollama/ollama");
            c = new OllamaContainer(bakedImage)
                    .withReuse(true)
                    .withLogConsumer(log)
                    .withImagePullPolicy(__ -> false);
            c.start();
        } catch (Exception e) {
            logger.warn("No backed image found. Downloading from scratch...", e);
            // baked image not yet built — pull model and commit it
            c = new OllamaContainer("ollama/ollama:latest").withLogConsumer(log);
            c.start();
            try {
                var result = c.execInContainer("ollama", "pull", MODEL);
                if (result.getExitCode() != 0)
                    throw new RuntimeException("ollama pull failed: " + result.getStderr());
                c.commitToImage(BAKED_IMAGE);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        OLLAMA = c;
    }

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", OLLAMA::getEndpoint);
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.chat.options.model", () -> MODEL);
    }
}
