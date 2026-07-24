package io.github.takahino.llmreviewer.git;

/** @param truncated maxChars 上限により切り詰められた場合 true(プロンプトへの明記に使う) */
public record DiffResult(String diffText, boolean truncated) {
}
