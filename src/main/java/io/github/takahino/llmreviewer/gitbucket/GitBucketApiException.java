package io.github.takahino.llmreviewer.gitbucket;

import io.github.takahino.llmreviewer.scm.ScmApiException;

/** GitBucket API 呼び出しの失敗(非2xx応答・通信エラー)を表す。 */
public class GitBucketApiException extends ScmApiException {

    public GitBucketApiException(String message) {
        this(message, -1);
    }

    public GitBucketApiException(String message, Throwable cause) {
        this(message, cause, -1);
    }

    public GitBucketApiException(String message, int statusCode) {
        super(message, statusCode);
    }

    public GitBucketApiException(String message, Throwable cause, int statusCode) {
        super(message, cause, statusCode);
    }
}
