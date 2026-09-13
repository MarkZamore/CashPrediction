package ru.cashprediction.core.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Версия приложения, одинаковая для всех трёх клиентов.
 *
 * <p>Номер релиза равен числу коммитов в ветке {@code main}: так «Версия 12» в окне «О программе»
 * указывает ровно на одну точку истории репозитория. Номер и хеш коммита подставляет сборка
 * (свойства Maven {@code app.release} и {@code app.commit}, их задаёт CI) в ресурс
 * {@code app.properties}. Локальная сборка без этих свойств получает номер 0 и считается
 * сборкой разработчика.</p>
 *
 * <p>Класс без изменяемого состояния: значения читаются один раз при загрузке класса.</p>
 */
public final class AppInfo {

    /** Имя ресурса внутри модуля core. */
    private static final String RESOURCE = "/ru/cashprediction/core/app.properties";

    private static final int RELEASE;
    private static final String COMMIT;

    static {
        Properties properties = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // Повреждённая сборка: версия не критична для работы, ниже подставятся значения разработчика.
        }
        RELEASE = parseRelease(properties.getProperty("release", "0"));
        COMMIT = normalizeCommit(properties.getProperty("commit", ""));
    }

    private AppInfo() {
    }

    /** @return номер релиза; 0 для локальной сборки разработчика */
    public static int release() {
        return RELEASE;
    }

    /** @return хеш коммита сборки или пустая строка, если сборка локальная */
    public static String commit() {
        return COMMIT;
    }

    /** @return {@code true}, если это сборка разработчика, а не релиз из CI */
    public static boolean isDevelopmentBuild() {
        return RELEASE <= 0;
    }

    /**
     * Текст версии для интерфейса.
     *
     * @return «Версия 12 (a1b2c3d)» для релиза или «Сборка разработчика» для локальной сборки
     */
    public static String displayVersion() {
        if (isDevelopmentBuild()) {
            return "Сборка разработчика";
        }
        return COMMIT.isEmpty()
                ? "Версия " + RELEASE
                : "Версия " + RELEASE + " (" + COMMIT.substring(0, Math.min(7, COMMIT.length())) + ")";
    }

    /**
     * Разбирает номер релиза. Нечисловое значение (например, неподставленный {@code ${app.release}}
     * при сборке из IDE) считается сборкой разработчика.
     *
     * @param text значение из ресурса
     * @return номер релиза или 0
     */
    static int parseRelease(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Оставляет только настоящий хеш коммита из шестнадцатеричных цифр.
     *
     * @param text значение из ресурса
     * @return хеш в нижнем регистре или пустая строка
     */
    static String normalizeCommit(String text) {
        String value = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
        return value.matches("[0-9a-f]{7,40}") ? value : "";
    }
}
