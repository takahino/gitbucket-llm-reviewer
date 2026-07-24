package io.github.takahino.llmreviewer.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Choice(ChatMessage message) {
}
