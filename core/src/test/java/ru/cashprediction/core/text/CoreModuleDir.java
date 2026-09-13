package ru.cashprediction.core.text;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Папка модуля {@code core} для тестов, которые читают исходники и документы репозитория
 * ({@code NoCyrillicLiteralsInNewCodeTest}, {@code UiTextCatalogTest}, {@code UiSpecCopyTest}).
 *
 * <p>Surefire передаёт свойство {@value #PROPERTY} ({@code core/pom.xml}). Без него (запуск из IDE) папка ищется от
 * рабочей папки: сама папка модуля или её подпапка {@code core} в корне репозитория. Так проверка не становится
 * молча пустой, если IDE запускает тесты из корня.</p>
 */
public final class CoreModuleDir {

    /** Системное свойство с абсолютной папкой модуля core. */
    public static final String PROPERTY = "core.basedir";

    /** Файл, по которому узнаётся папка модуля core. */
    private static final String MARKER = "src/main/java/ru/cashprediction/core";

    private CoreModuleDir() {
    }

    /**
     * Папка модуля core.
     *
     * @return абсолютный нормализованный путь
     */
    public static Path get() {
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property.strip()).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve(MARKER))) {
            return cwd;
        }
        Path fromRoot = cwd.resolve("core");
        return Files.isDirectory(fromRoot.resolve(MARKER)) ? fromRoot : cwd;
    }

    /**
     * Путь относительно папки модуля core.
     *
     * @param relative относительный путь, например {@code src/main/java} или {@code ../docs/ui-spec.md}
     * @return абсолютный нормализованный путь
     */
    public static Path resolve(String relative) {
        return get().resolve(relative).normalize();
    }
}
