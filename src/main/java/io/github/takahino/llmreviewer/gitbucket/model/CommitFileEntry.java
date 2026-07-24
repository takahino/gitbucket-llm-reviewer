package io.github.takahino.llmreviewer.gitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommitFileEntry(String filename, String status, String patch, Integer additions, Integer deletions) {
}
