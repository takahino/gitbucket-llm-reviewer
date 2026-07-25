package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;

import java.util.List;
import java.util.logging.Logger;

/**
 * PRへのレビューコメント投稿を担う。GitBucketはMarkdown中のHTMLタグをすべて除去する仕様のため
 * (&lt;details&gt;による折りたたみ表示ができない)、過去コメントには手を加えず新規コメント
 * (サマリ・指摘事項など複数件)を順に投稿するだけのシンプルな実装にしている。
 */
public class CommentPublisher {

    private static final Logger LOGGER = Logger.getLogger(CommentPublisher.class.getName());

    private final GitBucketClient client;
    private final boolean dryRun;

    public CommentPublisher(GitBucketClient client, boolean dryRun) {
        this.client = client;
        this.dryRun = dryRun;
    }

    public void publish(String owner, String repo, int issueNumber, List<String> newCommentBodies) {
        String prLabel = "%s/%s#%d".formatted(owner, repo, issueNumber);
        if (dryRun) {
            for (String body : newCommentBodies) {
                LOGGER.info("[dry-run] %s へのコメント投稿をスキップします:%n%s".formatted(prLabel, body));
            }
            return;
        }
        for (String body : newCommentBodies) {
            client.postIssueComment(owner, repo, issueNumber, body);
        }
    }
}
