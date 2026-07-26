package io.github.takahino.llmreviewer.util;

import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** java.util.logging をシンプルな1行フォーマットで初期化する。依存追加を避けるため独自フォーマッタを持つ。 */
public final class LogSetup {

    private LogSetup() {
    }

    public static void init(Level level) {
        // PollingServiceのカウントダウン表示等、Logger経由ではなくSystem.outへ直接出力する箇所でも
        // 日本語がWindowsのデフォルトコンソールコードページで文字化けしないようにする。
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));

        LogManager.getLogManager().reset();
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(level);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(level);
        try {
            // 日本語ログがWindowsのデフォルトコンソールコードページで文字化けするのを防ぐ
            handler.setEncoding(StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8がサポートされていません", e);
        }
        handler.setFormatter(new SimpleFormatter() {
            @Override
            public synchronized String format(LogRecord record) {
                String loggerName = record.getLoggerName() == null ? "" : shortenLoggerName(record.getLoggerName());
                String message = formatMessage(record);
                String throwable = "";
                if (record.getThrown() != null) {
                    throwable = System.lineSeparator() + record.getThrown();
                }
                return "%s [%s] %s - %s%s%n".formatted(
                        Instant.ofEpochMilli(record.getMillis()),
                        record.getLevel().getName(),
                        loggerName,
                        message,
                        throwable);
            }
        });
        rootLogger.addHandler(handler);
    }

    private static String shortenLoggerName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot < 0 ? fullName : fullName.substring(lastDot + 1);
    }
}
