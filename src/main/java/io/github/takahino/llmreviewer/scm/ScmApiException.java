package io.github.takahino.llmreviewer.scm;

/** SCM(Gitホスティングサービス)API呼び出しの失敗(非2xx応答・通信エラー)を表す基底例外。各プロバイダ実装はこれのサブクラスを持つ。 */
public abstract class ScmApiException extends RuntimeException {

    /** HTTPステータス由来でない失敗(通信エラー・JSON変換失敗等)の場合は -1。 */
    private final int statusCode;

    protected ScmApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    protected ScmApiException(String message, Throwable cause, int statusCode) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
