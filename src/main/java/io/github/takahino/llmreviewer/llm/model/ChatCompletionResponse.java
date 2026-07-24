package io.github.takahino.llmreviewer.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(List<Choice> choices) {
    public ChatCompletionResponse {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
