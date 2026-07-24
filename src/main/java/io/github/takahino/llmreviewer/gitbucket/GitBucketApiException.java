package io.github.takahino.llmreviewer.gitbucket;

/** GitBucket API 呼び出しの失敗(非2xx応答・通信エラー)を表す。 */
public class GitBucketApiException extends RuntimeException {

    public GitBucketApiException(String message) {
        super(message);
    }

    public GitBucketApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
