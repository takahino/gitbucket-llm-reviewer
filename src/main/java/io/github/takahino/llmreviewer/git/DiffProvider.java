package io.github.takahino.llmreviewer.git;

import io.github.takahino.llmreviewer.scm.model.PullRequest;

import java.util.List;

public interface DiffProvider {
    /** merge-base〜headの生unified diffテキストを返す(切り詰めなし。バッチ分割/切り詰めは呼び出し側の責務)。 */
    String getUnifiedDiff(String owner, String repo, PullRequest pr, List<String> excludeGlobs);
}
