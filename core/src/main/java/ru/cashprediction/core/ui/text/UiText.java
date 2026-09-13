package ru.cashprediction.core.ui.text;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import ru.cashprediction.core.text.TextCatalog;
import ru.cashprediction.core.text.Texts;

/**
 * Тексты интерфейса для моделей ядра (спецификация v2, §8): тонкая обёртка над общим каталогом
 * {@link Texts}, под именем, которым пользуется спецификация ({@code UiText.get(key, args…)}).
 *
 * <p><b>Файлы.</b> {@code core/src/main/resources/ru/cashprediction/core/ui/text/<область>_ru.properties} (UTF-8),
 * по файлу на область ({@link #AREAS}); поиск {@code <область>_<язык>} с запасным {@code <область>}, язык
 * зафиксирован {@code ru} (решение L13). Один набор файлов используют все три клиента: JavaFX и Swing получают
 * готовые строки в моделях, web — в JSON.</p>
 *
 * <p><b>Подстановки</b> {@code {0}}, {@code {1}} — собственные, за один проход, без {@link java.text.MessageFormat}.
 * <b>Отсутствующий ключ:</b> в строгом режиме ({@code -D}{@value #STRICT_PROPERTY}{@code =true}, так идут тесты
 * ядра) — исключение, иначе {@code !key!}. Подробности — {@link TextCatalog}.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class UiText {

    /** Системное свойство строгого режима. */
    public static final String STRICT_PROPERTY = TextCatalog.STRICT_PROPERTY;

    /** Папка ресурсов каталога. */
    public static final String RESOURCE_DIR = Texts.RESOURCE_DIR;

    /** Все области каталога в порядке загрузки. */
    public static final List<String> AREAS = Texts.AREAS;

    private UiText() {
    }

    /**
     * Текст по ключу с подстановкой аргументов.
     *
     * @param key  ключ каталога, например {@code status.msg.saved}
     * @param args значения для {@code {0}}, {@code {1}}, …
     * @return готовый текст; без строгого режима для неизвестного ключа — {@code !key!}
     * @throws IllegalStateException    в строгом режиме, если ключа нет
     * @throws IllegalArgumentException в строгом режиме, если в тексте есть {@code {n}} без аргумента
     */
    public static String get(String key, Object... args) {
        return Texts.get(key, args);
    }

    /**
     * Есть ли ключ в каталоге.
     *
     * @param key ключ
     * @return {@code true}, если текст задан
     */
    public static boolean has(String key) {
        return Texts.has(key);
    }

    /** @return все ключи каталога, отсортированные (неизменяемое множество) */
    public static Set<String> keys() {
        return Texts.catalog().keys();
    }

    /**
     * Шаблон текста без подстановки.
     *
     * @param key ключ
     * @return шаблон или пусто
     */
    public static Optional<String> template(String key) {
        return Texts.catalog().template(key);
    }

    /**
     * Область (файл), в которой задан ключ.
     *
     * @param key ключ
     * @return имя области, например {@code menu}, или пусто
     */
    public static Optional<String> area(String key) {
        return Texts.catalog().area(key);
    }

    /** @return повторы ключей; пустой список — повторов нет */
    public static List<String> duplicates() {
        return Texts.catalog().duplicates();
    }

    /** @return проблемы загрузки каталога; пустой список — все файлы прочитаны */
    public static List<String> loadProblems() {
        return Texts.catalog().loadProblems();
    }

    /**
     * Подставляет аргументы в произвольный шаблон (без строгого режима).
     *
     * @param template шаблон
     * @param args     аргументы
     * @return текст
     */
    public static String format(String template, Object... args) {
        return TextCatalog.format(template, args);
    }

    /**
     * Номера подстановок шаблона.
     *
     * @param template шаблон
     * @return отсортированное множество номеров
     */
    public static Set<Integer> placeholders(String template) {
        return TextCatalog.placeholders(template);
    }
}
