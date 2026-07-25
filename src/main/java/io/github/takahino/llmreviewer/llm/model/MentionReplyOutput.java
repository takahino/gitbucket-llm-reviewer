package io.github.takahino.llmreviewer.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** メンション応答(追質問/追レビュー)用にLLMに強制する構造化出力スキーマ。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MentionReplyOutput(String status, List<FileRequest> requestedFiles, String answer) {
    public MentionReplyOutput {
        requestedFiles = requestedFiles == null ? List.of() : List.copyOf(requestedFiles);
    }

    public boolean needsMoreContext() {
        return "need_more_context".equals(status);
    }
}
