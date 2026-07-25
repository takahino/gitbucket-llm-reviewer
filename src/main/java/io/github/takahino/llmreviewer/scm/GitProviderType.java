package io.github.takahino.llmreviewer.scm;

import java.util.Locale;

/** config.yml の provider 設定値に対応するGitホスティングサービス種別。 */
public enum GitProviderType {
    GITBUCKET,
    GITHUB,
    GITLAB,
    BITBUCKET;

    public static GitProviderType fromConfigValue(String value) {
        try {
            return GitProviderType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不明な provider です: %s (指定可能: gitbucket, github, gitlab, bitbucket)".formatted(value), e);
        }
    }
}
