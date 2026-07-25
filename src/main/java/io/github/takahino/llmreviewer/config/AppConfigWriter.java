package io.github.takahino.llmreviewer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 管理UIからのconfig.yml書き込みを担う。原子的な置き換えと、初回のみの手書き設定バックアップを行う。 */
public final class AppConfigWriter {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
            .findAndRegisterModules();
    private static final String HEADER_COMMENT =
            "# このファイルは管理UI(--ui)によって生成されました。項目の詳細説明は config.example.yml を参照してください。\n";

    private AppConfigWriter() {
    }

    /** config.yml を原子的に置き換える。同時呼び出しからの書き込み競合を避けるためプロセス内で直列化する。 */
    public static synchronized void write(Path configPath, AppConfig config) {
        try {
            backupIfNeeded(configPath);
            String yaml = HEADER_COMMENT + YAML_MAPPER.writeValueAsString(config);
            Path tmp = configPath.resolveSibling(configPath.getFileName().toString() + ".tmp");
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            moveAtomically(tmp, configPath);
        } catch (IOException e) {
            throw new UncheckedIOException("config.yml の書き込みに失敗しました: " + configPath, e);
        }
    }

    /** 手書きコメント入りの既存config.ymlをUIによる初回上書き前に一度だけ退避する。 */
    private static void backupIfNeeded(Path configPath) throws IOException {
        Path backup = configPath.resolveSibling(configPath.getFileName().toString() + ".bak");
        if (Files.isRegularFile(configPath) && !Files.exists(backup)) {
            Files.copy(configPath, backup);
        }
    }

    private static void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
