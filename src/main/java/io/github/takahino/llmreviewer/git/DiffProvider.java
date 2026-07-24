package io.github.takahino.llmreviewer.git;

import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;

import java.util.List;

public interface DiffProvider {
    DiffResult getUnifiedDiff(String owner, String repo, PullRequestInfo pr, List<String> excludeGlobs, int maxChars);
}
