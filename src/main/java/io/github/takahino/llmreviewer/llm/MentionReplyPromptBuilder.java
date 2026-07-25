package io.github.takahino.llmreviewer.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.rag.RagSearchResult;
import io.github.takahino.llmreviewer.rag.RetrievedChunk;
import io.github.takahino.llmreviewer.scm.model.IssueComment;
import io.github.takahino.llmreviewer.scm.model.PullRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PRコメントでのBotメンション応答(追質問/追レビュー)用プロンプトを組み立てる。
 * {@link PromptBuilder}(自動レビュー用)と構造は近いが、observed差分として
 * (1) レビュー観点(perspectiveGroups)が空でも動作する、(2) コメント履歴と質問文を含める、
 * という点が異なるため別クラスとしている。
 */
public class MentionReplyPromptBuilder {

    private final int maxAdditionalFiles;

    public MentionReplyPromptBuilder(int maxAdditionalFiles) {
        this.maxAdditionalFiles = maxAdditionalFiles;
    }

    public ChatMessage systemMessage() {
        String content = """
                あなたは経験豊富なソフトウェアエンジニアとして、プルリクエストのコードレビューを行うBotです。
                PRのコメント欄であなた自身がメンションされ、追加の質問または追加のレビュー依頼を受けました。
                出力は必ず次のJSONスキーマに従う1つのJSONオブジェクトのみとし、説明文やコードフェンスは付けないでください。

                {
                  "status": "complete または need_more_context",
                  "requestedFiles": [ { "path": "対象ファイルの相対パス", "reason": "必要な理由" } ],
                  "answer": "質問への回答、または追加レビュー結果(status=completeの場合は必須。Markdown形式の文字列)"
                }

                answerフィールドは、質問者が読んですぐ理解できる具体性を持たせ、次の方針で日本語のMarkdown形式で記述してください:
                - 質問に対しては直接的に回答し、根拠となるコードの実際の該当箇所をコードブロックとして引用してください。
                - 追加レビューを求められた場合は、指摘事項を箇条書きで具体的に示してください。
                - 「## レビュー観点」が提供されていない場合は、下記「## ユーザーからの質問/追加レビュー依頼」の
                  文面そのものを観点・指示として扱ってください。
                - diffだけでは判断できない場合(呼び出し先の実装・型定義などの確認が必要な場合)は、
                  status を "need_more_context" とし、requestedFiles で確認したいファイルを最大%d件まで要求してください。
                  要求したファイルの内容は次のメッセージで提供されるので、それを踏まえて再度同じスキーマで出力してください。
                - JSON文字列として出力するため、コードブロック内の改行は\\nでエスケープし、二重引用符は正しくエスケープすること。
                """.formatted(maxAdditionalFiles);
        return new SystemMessage(content);
    }

    public ChatMessage initialUserMessage(
            PullRequest pr,
            List<RepoReviewConfig.PerspectiveGroup> perspectiveGroups,
            List<String> repositoryFilePaths,
            Map<String, String> contextFiles,
            Map<String, String> perspectiveContextFiles,
            RagSearchResult ragResult,
            DiffResult diff,
            List<IssueComment> commentHistory,
            IssueComment triggerComment,
            String botUsername
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("## プルリクエスト情報\n");
        sb.append("- タイトル: ").append(pr.title()).append('\n');
        sb.append("- 本文:\n").append(isBlank(pr.body()) ? "(なし)" : pr.body()).append("\n\n");

        if (!perspectiveGroups.isEmpty()) {
            sb.append("## レビュー観点(このリポジトリの .review.yml による設定)\n");
            for (RepoReviewConfig.PerspectiveGroup group : perspectiveGroups) {
                sb.append("### ").append(group.label())
                        .append(" (対象: ").append(String.join(", ", group.matchedFiles())).append(")\n");
                for (RepoReviewConfig.PerspectiveEntry entry : group.perspectives()) {
                    sb.append("- ").append(entry.text()).append('\n');
                }
            }
            sb.append('\n');
        }

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

        if (!perspectiveContextFiles.isEmpty()) {
            sb.append("## 観点別の追加コンテキスト\n");
            perspectiveContextFiles.forEach((path, content) ->
                    sb.append("### ").append(path).append("\n```\n").append(content).append("\n```\n\n"));
        }

        appendRagSection(sb, "関連コード候補(ベクトル検索による自動抽出。参考情報であり正確性は保証されません。"
                        + "不足があれば requestedFiles で該当ファイルの取得を要求してください)",
                ragResult.relatedCode());
        appendRagSection(sb, "関連コーディング規約抜粋(ベクトル検索による自動抽出。参考情報です)",
                ragResult.knowledgeBase());

        sb.append("## 差分(unified diff)\n");
        if (diff.truncated()) {
            sb.append("(注意: 差分が上限文字数を超えたため切り詰められています)\n");
        }
        sb.append("```diff\n").append(diff.diffText()).append("\n```\n\n");

        if (!commentHistory.isEmpty()) {
            sb.append("## これまでのPRコメントのやり取り\n");
            for (IssueComment comment : commentHistory) {
                sb.append("### ").append(speakerLabel(comment, botUsername)).append("\n")
                        .append(comment.body()).append("\n\n");
            }
        }

        sb.append("## ユーザーからの質問/追加レビュー依頼\n");
        sb.append("(発言者: ").append(speakerLabel(triggerComment, botUsername)).append(")\n");
        sb.append(triggerComment.body()).append("\n");

        return new UserMessage(sb.toString());
    }

    public ChatMessage assistantMessage(String rawJsonContent) {
        return new AiMessage(rawJsonContent);
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
        return new UserMessage(sb.toString());
    }

    public ChatMessage forceCompleteMessage() {
        return new UserMessage(
                "追加のファイル提供は上限に達しました。現時点で得られている情報のみで、" +
                        "status を \"complete\" として最終的な回答を同じJSONスキーマで出力してください。");
    }

    private static String speakerLabel(IssueComment comment, String botUsername) {
        String login = comment.user() != null ? comment.user().login() : null;
        if (login == null || login.isBlank()) {
            return "(不明なユーザー)";
        }
        return login.equals(botUsername) ? "@" + login + "(Bot自身の過去の発言)" : "@" + login;
    }

    private static void appendRagSection(StringBuilder sb, String heading, List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        sb.append("## ").append(heading).append('\n');
        for (RetrievedChunk chunk : chunks) {
            sb.append("### ").append(chunk.sourcePath())
                    .append(" (score=").append("%.2f".formatted(chunk.score())).append(")\n")
                    .append("```\n").append(chunk.content()).append("\n```\n\n");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
