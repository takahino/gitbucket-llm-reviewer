package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.gitbucket.GitBucketApiException;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import io.github.takahino.llmreviewer.gitbucket.model.IssueComment;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PRへのレビューコメント投稿を担う。GitBucketにはコメントのpin留め等が無いため、
 * push毎に新規コメントが積み上がって読みにくくならないよう、投稿前にbot自身の過去コメントを
 * <details>タグで折りたたんでから新規コメントを投稿する。
 */
public class CommentPublisher {

    private static final Logger LOGGER = Logger.getLogger(CommentPublisher.class.getName());

    private final GitBucketClient client;
    private final boolean foldPreviousComments;
    private final boolean dryRun;

    public CommentPublisher(GitBucketClient client, boolean foldPreviousComments, boolean dryRun) {
        this.client = client;
        this.foldPreviousComments = foldPreviousComments;
        this.dryRun = dryRun;
    }

    public void publish(String owner, String repo, int issueNumber, String newCommentBody) {
        String prLabel = "%s/%s#%d".formatted(owner, repo, issueNumber);
        if (dryRun) {
            LOGGER.info("[dry-run] %s へのコメント投稿をスキップします:%n%s".formatted(prLabel, newCommentBody));
            return;
        }
        if (foldPreviousComments) {
            foldPreviousBotComments(owner, repo, issueNumber);
        }
        client.postIssueComment(owner, repo, issueNumber, newCommentBody);
    }

    private void foldPreviousBotComments(String owner, String repo, int issueNumber) {
        List<IssueComment> comments;
        try {
            comments = client.listIssueComments(owner, repo, issueNumber);
        } catch (GitBucketApiException e) {
            LOGGER.log(Level.WARNING,
                    "過去コメント一覧の取得に失敗したため折りたたみをスキップします: %s/%s#%d".formatted(owner, repo, issueNumber), e);
            return;
        }
        for (IssueComment comment : comments) {
            if (!CommentFormatter.isBotComment(comment.body()) || comment.body().stripLeading().startsWith("<details>")) {
                continue;
            }
            String folded = "<details>\n<summary>過去のレビュー結果</summary>\n\n" + comment.body() + "\n\n</details>\n";
            try {
                client.updateIssueComment(owner, repo, comment.id(), folded);
            } catch (GitBucketApiException e) {
                LOGGER.log(Level.WARNING,
                        "過去コメントの折りたたみに失敗しました(id=%d): %s".formatted(comment.id(), e.getMessage()), e);
            }
        }
    }
}
