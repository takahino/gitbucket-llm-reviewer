package io.github.takahino.llmreviewer.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Finding(String file, Integer line, String severity, String perspective, String comment) {

    /** LLMの自己申告のseverityは表記揺れ・不正値がありうるため、既知の3値以外は"info"に丸めて返す。 */
    public String normalizedSeverity() {
        if (severity == null) {
            return "info";
        }
        String normalized = severity.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "error", "warning" -> normalized;
            default -> "info";
        };
    }
}
