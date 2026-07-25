package io.github.takahino.llmreviewer.gitbucket;

/** GitBucket API 呼び出しの失敗(非2xx応答・通信エラー)を表す。 */
public class GitBucketApiException extends RuntimeException {

    /** HTTPステータス由来でない失敗(通信エラー・JSON変換失敗等)の場合は -1。 */
    private final int statusCode;

    public GitBucketApiException(String message) {
        this(message, -1);
    }

    public GitBucketApiException(String message, Throwable cause) {
        this(message, cause, -1);
    }

    public GitBucketApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public GitBucketApiException(String message, Throwable cause, int statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
