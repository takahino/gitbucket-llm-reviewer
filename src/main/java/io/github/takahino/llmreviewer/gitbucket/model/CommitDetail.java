package io.github.takahino.llmreviewer.gitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommitDetail(String sha, List<CommitFileEntry> files) {
    public CommitDetail {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
