package io.github.takahino.llmreviewer.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.github.takahino.llmreviewer.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class EmbeddingModelFactoryTest {

    private AppConfig.RagConfig configFor(String provider) {
        return new AppConfig.RagConfig(
                true, provider, "http://localhost:11434", "test-model", "",
                5, 0.5, 500, 50, 100, List.of(".java"), null);
    }

    @Test
    void createsOllamaEmbeddingModelForOllamaProvider() {
        EmbeddingModel model = EmbeddingModelFactory.create(configFor("ollama"));
        assertInstanceOf(OllamaEmbeddingModel.class, model);
    }

    @Test
    void createsOpenAiEmbeddingModelForOpenAiCompatibleProvider() {
        EmbeddingModel model = EmbeddingModelFactory.create(configFor("openai-compatible"));
        assertInstanceOf(OpenAiEmbeddingModel.class, model);
    }
}
