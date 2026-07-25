package io.github.takahino.llmreviewer.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.github.takahino.llmreviewer.config.AppConfig;

/** rag.embeddingProvider の設定に応じて {@link EmbeddingModel} を生成する。 */
public final class EmbeddingModelFactory {

    private EmbeddingModelFactory() {
    }

    public static EmbeddingModel create(AppConfig.RagConfig config) {
        return switch (config.embeddingProvider()) {
            case "ollama" -> OllamaEmbeddingModel.builder()
                    .baseUrl(config.embeddingBaseUrl())
                    .modelName(config.embeddingModel())
                    .build();
            case "openai-compatible" -> OpenAiEmbeddingModel.builder()
                    .baseUrl(config.embeddingBaseUrl())
                    .apiKey(config.embeddingApiKey().isBlank() ? "unused" : config.embeddingApiKey())
                    .modelName(config.embeddingModel())
                    .build();
            default -> throw new IllegalArgumentException(
                    "未知の rag.embeddingProvider です: " + config.embeddingProvider());
        };
    }
}
