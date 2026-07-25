package io.github.takahino.llmreviewer.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * .review.yml の perspectives リストをパースするデシリアライザ。
 * 各要素はスカラー文字列(observationテキストのみ)、または
 * `perspective`(テキスト)/`context`(.review/配下の追加コンテキストファイル名一覧)を持つマッピングのどちらでも書ける。
 */
public class PerspectiveEntryListDeserializer extends JsonDeserializer<List<RepoReviewConfig.PerspectiveEntry>> {

    @Override
    public List<RepoReviewConfig.PerspectiveEntry> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode arrayNode = p.getCodec().readTree(p);
        List<RepoReviewConfig.PerspectiveEntry> result = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            result.add(toEntry(node));
        }
        return result;
    }

    private RepoReviewConfig.PerspectiveEntry toEntry(JsonNode node) {
        if (node.isTextual()) {
            return new RepoReviewConfig.PerspectiveEntry(node.asText(), List.of());
        }
        String text = node.path("perspective").asText("");
        List<String> context = new ArrayList<>();
        for (JsonNode contextNode : node.path("context")) {
            context.add(contextNode.asText());
        }
        return new RepoReviewConfig.PerspectiveEntry(text, context);
    }
}
