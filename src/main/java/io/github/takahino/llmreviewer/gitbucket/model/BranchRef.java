package io.github.takahino.llmreviewer.gitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** PR の base/head を表す(GitHub 互換 JSON)。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BranchRef(String ref, String sha) {
}
