package io.github.takahino.llmreviewer.gitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitUser(String login) {
}
