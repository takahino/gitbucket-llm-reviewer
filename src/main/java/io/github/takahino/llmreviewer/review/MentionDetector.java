package io.github.takahino.llmreviewer.review;

import java.util.regex.Pattern;

/** コメント本文にBotへの `@username` メンションが含まれるかを判定する。 */
public final class MentionDetector {

    private MentionDetector() {
    }

    /**
     * commentBody中に {@code @botUsername} が、前後を英数字・'_'・'-'・'.' で囲まれない形
     * (GitBucketのユーザー名に使える文字の続きになっていない形)で出現するかを判定する。
     * これにより {@code @botfoo} のような別ユーザー名への部分一致誤検知を防ぐ。
     */
    public static boolean mentions(String commentBody, String botUsername) {
        if (commentBody == null || commentBody.isBlank() || botUsername == null || botUsername.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile(
                "(?<![A-Za-z0-9_.-])@" + Pattern.quote(botUsername) + "(?![A-Za-z0-9_.-])");
        return pattern.matcher(commentBody).find();
    }
}
