package io.github.takahino.llmreviewer.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** LLM に強制する構造化出力スキーマ。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewOutput(String status, List<FileRequest> requestedFiles, String summary, List<Finding> findings) {
    public ReviewOutput {
        requestedFiles = requestedFiles == null ? List.of() : List.copyOf(requestedFiles);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public boolean needsMoreContext() {
        return "need_more_context".equals(status);
    }
}
