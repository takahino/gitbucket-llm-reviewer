package io.github.takahino.llmreviewer.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** リポジトリ側 .review.yml のパース。存在しない/壊れている場合はデフォルト観点で継続する。 */
public final class RepoReviewConfigLoader {

    private static final Logger LOGGER = Logger.getLogger(RepoReviewConfigLoader.class.getName());

    /**
     * パース中に収集した警告(未知キー等)を {@link DeserializationContext} 経由でやり取りするための属性キー。
     * {@link PerspectiveEntryListDeserializer} など、通常のプロパティバインディングを経由しないカスタム
     * デシリアライザからも同じ警告リストへ追記できるよう公開している。
     */
    public static final String WARNINGS_ATTRIBUTE = "repoReviewConfigWarnings";

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addHandler(new UnknownKeyProblemHandler());

    private RepoReviewConfigLoader() {
    }

    /** パース結果と、パース中に検出した警告(未知キーのtypo等)の一覧。 */
    public record ParseResult(RepoReviewConfig config, List<String> warnings) {
        public ParseResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** @param yamlContent .review.yml の生の中身。取得できなかった場合は null を渡す */
    public static ParseResult parse(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            return new ParseResult(RepoReviewConfig.defaultConfig(), List.of());
        }
        List<String> warnings = new ArrayList<>();
        try {
            RepoReviewConfig config = MAPPER.reader()
                    .withAttribute(WARNINGS_ATTRIBUTE, warnings)
                    .forType(RepoReviewConfig.class)
                    .readValue(yamlContent);
            return new ParseResult(config, warnings);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, ".review.yml の解析に失敗したため、デフォルト観点を使用します", e);
            String detail = e instanceof JsonProcessingException jpe ? jpe.getOriginalMessage() : e.getMessage();
            String message = ".review.yml の構文解析に失敗しました: " + (detail == null ? e.getClass().getSimpleName() : detail);
            return new ParseResult(RepoReviewConfig.defaultConfig(), List.of(message));
        }
    }

    /**
     * トップレベル/{@code paths.<glob>} 配下の未知キー(typo等)を、例外にせず警告として収集するハンドラ。
     * {@code RepoReviewConfig}/{@code PathConfig} はどちらも record なので、既知キー一覧は
     * {@link Class#getRecordComponents()} から動的に取得しスキーマと自動的に同期させる。
     */
    private static final class UnknownKeyProblemHandler extends DeserializationProblemHandler {
        @Override
        public boolean handleUnknownProperty(
                DeserializationContext ctxt, JsonParser p, JsonDeserializer<?> deserializer,
                Object beanOrClass, String propertyName) throws IOException {
            Class<?> type = beanOrClass instanceof Class<?> c ? c : beanOrClass.getClass();
            String suggestion = closestMatch(propertyName, knownKeysFor(type))
                    .map(match -> "(もしかして: " + match + " ?)")
                    .orElse("");
            addWarning(ctxt, "%s に未知のキー '%s' があります(無視されます)%s"
                    .formatted(describeLocation(type), propertyName, suggestion));
            p.skipChildren();
            return true;
        }
    }

    private static String describeLocation(Class<?> type) {
        if (type == RepoReviewConfig.class) {
            return ".review.yml のトップレベル";
        }
        if (type == RepoReviewConfig.PathConfig.class) {
            return "paths 配下の設定";
        }
        return type.getSimpleName();
    }

    private static List<String> knownKeysFor(Class<?> type) {
        if (!type.isRecord()) {
            return List.of();
        }
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /** {@code ctxt} の警告リストへ追記する。呼び出し元は {@link #WARNINGS_ATTRIBUTE} でリストを渡しておく必要がある。 */
    @SuppressWarnings("unchecked")
    static void addWarning(DeserializationContext ctxt, String message) {
        Object attribute = ctxt.getAttribute(WARNINGS_ATTRIBUTE);
        if (attribute instanceof List<?> list) {
            ((List<String>) list).add(message);
        }
    }

    /** 編集距離2以内で最も近い既知キーを返す(typoの「もしかして」候補)。 */
    private static Optional<String> closestMatch(String input, List<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = levenshteinDistance(input, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return (best != null && bestDistance <= 2) ? Optional.of(best) : Optional.empty();
    }

    private static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
