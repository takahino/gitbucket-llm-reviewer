package io.github.takahino.llmreviewer.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.takahino.llmreviewer.llm.model.ReviewOutput;

import java.io.IOException;

/** LLM応答からコードフェンス・前置きテキストを除去し、構造化出力(JSON)を抽出・パースする。 */
public final class LlmResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmResponseParser() {
    }

    public static ReviewOutput parse(String rawContent) {
        return parse(rawContent, ReviewOutput.class);
    }

    public static <T> T parse(String rawContent, Class<T> type) {
        if (rawContent == null || rawContent.isBlank()) {
            // reasoning系モデルがmaxTokensを思考トークンで使い切った場合等に、contentが空/nullで
            // 返ってくることがある。呼び出し元(chatAndParse)の矯正リトライに委ねられるよう、
            // NPEではなく捕捉可能な例外にする。
            throw new LlmClientException("LLM応答のcontentが空でした(reasoning用トークンを使い切った可能性があります)");
        }
        String jsonText = extractJson(rawContent);
        try {
            return MAPPER.readValue(jsonText, type);
        } catch (IOException e) {
            throw new LlmClientException("LLM応答のJSON抽出に失敗しました: " + rawContent, e);
        }
    }

    /** コードフェンスを除去した上で、文字列リテラル内の括弧を無視しつつ最初のバランスした {...} を抽出する。 */
    private static String extractJson(String text) {
        String stripped = stripCodeFence(text);
        int start = stripped.indexOf('{');
        if (start < 0) {
            return stripped;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return stripped.substring(start, i + 1);
                }
            }
        }
        return stripped.substring(start);
    }

    private static String stripCodeFence(String text) {
        String s = text.strip();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        int lastFence = s.lastIndexOf("```");
        if (firstNewline > 0 && lastFence > firstNewline) {
            return s.substring(firstNewline + 1, lastFence).strip();
        }
        return s;
    }
}
