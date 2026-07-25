package io.github.takahino.llmreviewer.scm;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** メンション応答機能でBot自身を識別するためのユーザー名を解決する。 */
public final class BotIdentityResolver {

    private static final Logger LOGGER = Logger.getLogger(BotIdentityResolver.class.getName());

    private BotIdentityResolver() {
    }

    /**
     * configuredBotUsername が非空ならAPI呼び出しを行わずそれを採用する。
     * 空の場合は認証済みユーザー取得APIから解決を試み、失敗した場合は
     * メンション応答機能そのものを無効化する意図で empty を返す(自動レビュー機能は継続させるため)。
     */
    public static Optional<String> resolve(ScmClient client, String configuredBotUsername) {
        if (configuredBotUsername != null && !configuredBotUsername.isBlank()) {
            return Optional.of(configuredBotUsername);
        }
        try {
            String login = client.getAuthenticatedUser().login();
            if (login == null || login.isBlank()) {
                LOGGER.warning("APIから取得したユーザー名が空のため、メンション応答機能を無効化します");
                return Optional.empty();
            }
            return Optional.of(login);
        } catch (ScmApiException e) {
            LOGGER.log(Level.WARNING,
                    "APIからのBotユーザー名解決に失敗したため、メンション応答機能を無効化します"
                            + "(gitbucket.botUsername を明示設定すると回避できます)", e);
            return Optional.empty();
        }
    }
}
