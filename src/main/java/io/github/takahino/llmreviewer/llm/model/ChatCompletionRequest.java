package io.github.takahino.llmreviewer.llm.model;

import java.util.List;

/** OpenAI 互換 /chat/completions のリクエストボディ(snake_case で送信)。 */
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        ResponseFormat responseFormat
) {
}
