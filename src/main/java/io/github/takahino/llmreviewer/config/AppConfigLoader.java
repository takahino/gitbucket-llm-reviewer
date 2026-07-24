package io.github.takahino.llmreviewer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AppConfigLoader() {
    }

    public static AppConfig load(Path configPath) {
        if (!Files.isRegularFile(configPath)) {
            throw new IllegalArgumentException("設定ファイルが見つかりません: " + configPath);
        }
        try {
            return MAPPER.readValue(configPath.toFile(), AppConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("設定ファイルの読み込みに失敗しました: " + configPath, e);
        }
    }
}
