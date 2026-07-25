package io.github.takahino.llmreviewer.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * .review.yml の perspectives リストをパースするデシリアライザ。
 * 各要素はスカラー文字列(observationテキストのみ)、または
 * `perspective`(テキスト)/`context`(.review/配下の追加コンテキストファイル名一覧)を持つマッピングのどちらでも書ける。
 */
public class PerspectiveEntryListDeserializer extends JsonDeserializer<List<RepoReviewConfig.PerspectiveEntry>> {

    private static final List<String> KNOWN_KEYS = List.of("perspective", "context");

    @Override
    public List<RepoReviewConfig.PerspectiveEntry> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode arrayNode = p.getCodec().readTree(p);
        List<RepoReviewConfig.PerspectiveEntry> result = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            result.add(toEntry(node, ctxt));
        }
        return result;
    }

    private RepoReviewConfig.PerspectiveEntry toEntry(JsonNode node, DeserializationContext ctxt) {
        if (node.isTextual()) {
            return new RepoReviewConfig.PerspectiveEntry(node.asText(), List.of());
        }
        warnOnUnknownKeys(node, ctxt);
        String text = node.path("perspective").asText("");
        List<String> context = new ArrayList<>();
        for (JsonNode contextNode : node.path("context")) {
            context.add(contextNode.asText());
        }
        return new RepoReviewConfig.PerspectiveEntry(text, context);
    }

    /**
     * カスタムデシリアライザ内でJsonNodeを手動で読んでいるため、通常のプロパティバインディングを経由する
     * {@code DeserializationProblemHandler}(RepoReviewConfigLoader側)では未知キーを検知できない。
     * ここで perspective/context 以外のキーを個別にチェックし、同じ警告収集ロジック({@link RepoReviewConfigLoader#warnUnknownKey})
     * へ委譲する。
     */
    private void warnOnUnknownKeys(JsonNode node, DeserializationContext ctxt) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!KNOWN_KEYS.contains(name)) {
                RepoReviewConfigLoader.warnUnknownKey(ctxt, "perspectives のエントリ", name, KNOWN_KEYS);
            }
        }
    }
}
