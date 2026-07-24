package io.github.takahino.llmreviewer.util;

import org.mozilla.universalchardet.UniversalDetector;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * レビュー対象のソースコードはUTF-8とは限らない(Shift_JIS/EUC-JP等の日本語コードベースも多い)ため、
 * バイト列から文字コードを自動判定してデコードする。判定できない場合はUTF-8にフォールバックする。
 */
public final class CharsetDetector {

    private CharsetDetector() {
    }

    public static String decode(byte[] bytes) {
        UniversalDetector detector = new UniversalDetector();
        detector.handleData(bytes, 0, bytes.length);
        detector.dataEnd();
        String detected = detector.getDetectedCharset();
        detector.reset();

        Charset charset = StandardCharsets.UTF_8;
        if (detected != null) {
            try {
                charset = Charset.forName(detected);
            } catch (UnsupportedCharsetException ignored) {
                // 未知のcharset名が返った場合はUTF-8フォールバックを維持
            }
        }
        return new String(bytes, charset);
    }
}
