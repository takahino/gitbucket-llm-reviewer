package io.github.takahino.llmreviewer.gitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PullRequestInfo(
        int number,
        String title,
        String body,
        String state,
        GitUser user,
        BranchRef head,
        BranchRef base,
        String updatedAt,
        String htmlUrl
) {
}
