package ru.cashprediction.core.format;

import java.util.List;
import java.util.Locale;

/**
 * Чистые правила служебных имён CashMemory, общие для проверки плана и файловой раскладки.
 *
 * <p>Проверяет только строки, без обращения к файловой системе и без пользовательских сообщений.
 * Имена формата не локализуются; проверка реальных путей и ссылок принадлежит файловому слою.</p>
 */
public final class ReservedPlanNames {

    /** Имена планов без расширения, занятые служебными файлами; список неизменяем. */
    public static final List<String> NAMES = List.of(
            "settings", "web-session", "web-session.plan", "session-fx", "session-swing",
            "web-reconnect", "web-reconnect-lock");

    /** Начало имени XML-снимка сессии. */
    public static final String SESSION_XML_PREFIX = "session-";

    /** Расширение XML-снимка сессии. */
    public static final String SESSION_XML_SUFFIX = ".xml";

    private ReservedPlanNames() {
    }

    /**
     * Проверяет резервирование имени плана без расширения.
     *
     * @param nameWithoutExtension имя без {@code .md}; {@code null} не зарезервирован
     * @return занято ли имя служебным файлом или временной публикацией ключа переподключения;
     *         регистр и пробелы по краям не учитываются
     */
    public static boolean isReservedPlanName(String nameWithoutExtension) {
        if (nameWithoutExtension == null) {
            return false;
        }
        String name = nameWithoutExtension.strip().toLowerCase(Locale.ROOT);
        return NAMES.contains(name) || name.startsWith("web-reconnect-tmp-");
    }

    /**
     * Проверяет служебное имя файла по строке, включая временные файлы и XML-снимки.
     *
     * @param fileName имя с расширением; {@code null} не является служебным именем
     * @return служебное ли имя; регистр, пробелы по краям и конечные точки-алиасы Windows не учитываются
     */
    public static boolean isServiceFileName(String fileName) {
        if (fileName == null) return false;
        String name = fileName.strip().toLowerCase(Locale.ROOT);
        // Windows допускает алиасы с конечными точками: они не должны обходить запрет записи.
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1).stripTrailing();
        return name.endsWith(".tmp")
                || (name.endsWith(".md") && isReservedPlanName(name.substring(0, name.length() - 3)))
                || (name.startsWith(SESSION_XML_PREFIX) && name.endsWith(SESSION_XML_SUFFIX));
    }
}
