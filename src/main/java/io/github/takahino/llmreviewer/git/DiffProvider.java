package io.github.takahino.llmreviewer.git;

import io.github.takahino.llmreviewer.scm.model.PullRequest;

import java.util.List;

public interface DiffProvider {
    DiffResult getUnifiedDiff(String owner, String repo, PullRequest pr, List<String> excludeGlobs, int maxChars);
}
