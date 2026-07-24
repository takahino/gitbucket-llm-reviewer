package io.github.takahino.llmreviewer.git;

/** JGit を用いたローカルミラー操作(fetch/diff/読み込み)の失敗を表す。 */
public class GitMirrorException extends RuntimeException {

    public GitMirrorException(String message) {
        super(message);
    }

    public GitMirrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
