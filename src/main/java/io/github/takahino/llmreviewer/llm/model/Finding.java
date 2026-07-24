package io.github.takahino.llmreviewer.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Finding(String file, Integer line, String severity, String perspective, String comment) {
}
