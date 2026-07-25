package io.github.takahino.llmreviewer.llm;

import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;
import io.github.takahino.llmreviewer.llm.model.ChatMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** レビュー用プロンプト(システム/ユーザー/追加ファイル/強制確定メッセージ)を組み立てる。 */
public class PromptBuilder {

    private final int maxAdditionalFiles;

    public PromptBuilder(int maxAdditionalFiles) {
        this.maxAdditionalFiles = maxAdditionalFiles;
    }

    public ChatMessage systemMessage() {
        String content = """
                あなたは経験豊富なソフトウェアエンジニアとして、プルリクエストのコードレビューを行います。
                出力は必ず次のJSONスキーマに従う1つのJSONオブジェクトのみとし、説明文やコードフェンスは付けないでください。

                {
                  "status": "complete または need_more_context",
                  "requestedFiles": [ { "path": "対象ファイルの相対パス", "reason": "必要な理由" } ],
                  "summary": "変更内容の日本語サマリ(status=completeの場合は必須)",
                  "findings": [
                    { "file": "相対パス", "line": 変更後(new)ファイル基準の行番号(不明ならnull), "severity": "error"|"warning"|"info",
                      "perspective": "対応する観点", "comment": "指摘内容と修正提案" }
                  ]
                }

                diffだけでは判断できない場合(呼び出し先の実装・型定義・既存の命名規約などの確認が必要な場合)は、
                status を "need_more_context" とし、requestedFiles で確認したいファイルを最大%d件まで要求してください。
                要求したファイルの内容は次のメッセージで提供されるので、それを踏まえて再度同じスキーマで出力してください。
                """.formatted(maxAdditionalFiles);
        return new ChatMessage("system", content);
    }

    public ChatMessage initialUserMessage(
            PullRequestInfo pr,
            RepoReviewConfig repoConfig,
            List<RepoReviewConfig.PerspectiveGroup> perspectiveGroups,
            List<String> repositoryFilePaths,
            Map<String, String> contextFiles,
            DiffResult diff,
            String incrementalPreviousHeadSha
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("## プルリクエスト情報\n");
        sb.append("- タイトル: ").append(pr.title()).append('\n');
        sb.append("- 本文:\n").append(isBlank(pr.body()) ? "(なし)" : pr.body()).append("\n\n");

        sb.append("## レビュー観点\n");
        for (RepoReviewConfig.PerspectiveGroup group : perspectiveGroups) {
            sb.append("### ").append(group.label())
                    .append(" (対象: ").append(String.join(", ", group.matchedFiles())).append(")\n");
            group.perspectives().forEach(p -> sb.append("- ").append(p).append('\n'));
        }
        sb.append('\n');

        if (!repositoryFilePaths.isEmpty()) {
            sb.append("## リポジトリのファイル一覧(追加確認したい場合の参考。全").append(repositoryFilePaths.size()).append("件)\n");
            repositoryFilePaths.forEach(p -> sb.append("- ").append(p).append('\n'));
            sb.append('\n');
        }

        if (!contextFiles.isEmpty()) {
            sb.append("## 常時提供されるコンテキストファイル\n");
            contextFiles.forEach((path, content) ->
                    sb.append("### ").append(path).append("\n```\n").append(content).append("\n```\n\n"));
        }

        sb.append("## 差分(unified diff)\n");
        if (incrementalPreviousHeadSha != null) {
            sb.append("(注意: これは前回レビュー(head: ").append(shortSha(incrementalPreviousHeadSha))
                    .append(")以降の増分差分です。PR全体の差分ではありません)\n");
        }
        if (diff.truncated()) {
            sb.append("(注意: 差分が上限文字数を超えたため切り詰められています)\n");
        }
        sb.append("```diff\n").append(diff.diffText()).append("\n```\n");

        return new ChatMessage("user", sb.toString());
    }

    public ChatMessage assistantMessage(String rawJsonContent) {
        return new ChatMessage("assistant", rawJsonContent);
    }

    public ChatMessage additionalFilesMessage(Map<String, Optional<String>> resolvedFiles) {
        StringBuilder sb = new StringBuilder("## 要求されたファイルの内容\n");
        resolvedFiles.forEach((path, content) -> {
            if (content.isPresent()) {
                sb.append("### ").append(path).append("\n```\n").append(content.get()).append("\n```\n\n");
            } else {
                sb.append("### ").append(path)
                        .append("\n(このパスは見つかりませんでした。存在するパスの中から近いものを再確認してください)\n\n");
            }
        });
        sb.append("上記を踏まえて、同じJSONスキーマで出力してください。\n");
        return new ChatMessage("user", sb.toString());
    }

    public ChatMessage forceCompleteMessage() {
        return new ChatMessage("user",
                "追加のファイル提供は上限に達しました。現時点で得られている情報のみで、" +
                        "status を \"complete\" として最終的なレビュー結果を同じJSONスキーマで出力してください。");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String shortSha(String sha) {
        return sha.length() > 10 ? sha.substring(0, 10) : sha;
    }
}
