package io.github.takahino.llmreviewer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.logging.Level;
import java.util.logging.Logger;

/** リポジトリ側 .review.yml のパース。存在しない/壊れている場合はデフォルト観点で継続する。 */
public final class RepoReviewConfigLoader {

    private static final Logger LOGGER = Logger.getLogger(RepoReviewConfigLoader.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RepoReviewConfigLoader() {
    }

    /** @param yamlContent .review.yml の生の中身。取得できなかった場合は null を渡す */
    public static RepoReviewConfig parse(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            return RepoReviewConfig.defaultConfig();
        }
        try {
            return MAPPER.readValue(yamlContent, RepoReviewConfig.class);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, ".review.yml の解析に失敗したため、デフォルト観点を使用します", e);
            return RepoReviewConfig.defaultConfig();
        }
    }
}
