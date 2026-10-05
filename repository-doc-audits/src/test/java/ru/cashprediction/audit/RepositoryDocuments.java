package ru.cashprediction.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Читает строго обязательные документы репозитория и сохраняет прежние правила поиска символов. */
final class RepositoryDocuments {
    /** Четыре спецификации, которые намеренно не входят в доставку исходников. */
    static final List<String> DOCUMENTS = List.of("docs/ui-spec.md", "docs/design/ui-spec-v2.md",
            "docs/FORMAT.md", "docs/ui-protocol.md");

    /** Любая прежняя форма длинного или среднего тире, включая escape и HTML-сущности. */
    private static final Pattern DASH = Pattern.compile("[" + (char) 0x2013 + (char) 0x2014 + "]"
            + "|\\\\u+201[34]|&(?:mdash|ndash);|&#0*821[12];|&#x0*201[34];", Pattern.CASE_INSENSITIVE);

    private RepositoryDocuments() { }

    /** Возвращает заданный Maven корень; отсутствие настройки не заменяется эвристикой. */
    static Path root() {
        String value = System.getProperty("repository.basedir");
        if (value == null || value.isBlank()) throw new IllegalStateException("repository.basedir is required");
        return Path.of(value).toAbsolutePath().normalize();
    }

    /** Читает обязательный файл как UTF-8 тем же способом, что прежняя проверка core. */
    static String read(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        if (!Files.isRegularFile(file)) throw new IOException("Required repository document missing: " + file);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Находит каждую строку с прежними запрещёнными формами тире. */
    static List<String> dashes(String name, String text) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int line = 0; line < lines.length; line++) {
            if (DASH.matcher(lines[line]).find()) found.add(name + ":" + (line + 1) + ": " + lines[line].strip());
        }
        return found;
    }

    /** Находит каждую строку с U+2212, не подменяя прежнюю проверку знаком дефиса. */
    static List<String> minuses(String name, String text) {
        List<String> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int line = 0; line < lines.length; line++) {
            if (lines[line].indexOf((char) 0x2212) >= 0) found.add(name + ":" + (line + 1) + ": " + lines[line].strip());
        }
        return found;
    }
}
