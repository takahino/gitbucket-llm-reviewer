package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.llm.model.Finding;
import io.github.takahino.llmreviewer.llm.model.ReviewOutput;

import java.util.List;

/** LLMのレビュー結果をGitBucketのPRコメント用Markdownに整形する。 */
public final class CommentFormatter {

    private CommentFormatter() {
    }

    /** 冪等性の保険として、同一headShaに対する重複投稿判定に使えるマーカー。 */
    public static String marker(String headSha) {
        return "<!-- gitbucket-llm-reviewer: %s -->".formatted(headSha);
    }

    public static String format(
            ReviewOutput output,
            String headSha,
            String modelName,
            List<String> referencedAdditionalFiles,
            int maxComments
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(marker(headSha)).append('\n');
        sb.append("## 変更サマリ\n")
                .append(isBlank(output.summary()) ? "(サマリなし)" : output.summary())
                .append("\n\n");

        sb.append("## 指摘事項\n");
        List<Finding> findings = output.findings();
        if (findings.isEmpty()) {
            sb.append("特に指摘事項はありませんでした。\n");
        } else {
            boolean truncated = findings.size() > maxComments;
            List<Finding> displayed = truncated ? findings.subList(0, maxComments) : findings;
            for (Finding f : displayed) {
                String location = f.line() != null ? "%s:%d".formatted(f.file(), f.line()) : String.valueOf(f.file());
                sb.append("- **[").append(severityLabel(f.severity())).append("] ").append(location).append("**");
                if (!isBlank(f.perspective())) {
                    sb.append(" (観点: ").append(f.perspective()).append(")");
                }
                sb.append("\n  ").append(f.comment()).append("\n");
            }
            if (truncated) {
                sb.append("\n(他 ").append(findings.size() - maxComments).append(" 件の指摘は表示上限のため省略されました)\n");
            }
        }

        sb.append("\n---\n");
        sb.append("モデル: `").append(modelName).append("` / head: `").append(shortSha(headSha)).append("`");
        if (!referencedAdditionalFiles.isEmpty()) {
            sb.append(" / 参照した追加ファイル: ").append(String.join(", ", referencedAdditionalFiles));
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String severityLabel(String severity) {
        if ("error".equals(severity) || "warning".equals(severity)) {
            return severity;
        }
        return "info";
    }

    private static String shortSha(String sha) {
        return sha.length() > 10 ? sha.substring(0, 10) : sha;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
