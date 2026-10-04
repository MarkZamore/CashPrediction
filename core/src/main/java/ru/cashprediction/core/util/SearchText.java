package ru.cashprediction.core.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Чистая подготовка текстового поиска, без зависимости от Markdown, планов и локализации интерфейса.
 * Нормализация сохраняет прежнюю семантику RuFormats.normalize: обработка кодовых точек Unicode,
 * Character.toLowerCase, «ё/е» и оба вида пробельных символов Character.
 * Грамматика файлов продолжает использовать свой прежний нормализатор без изменений.
 */
public final class SearchText {
    /** Буква «ё» после перевода в нижний регистр. */
    private static final int YO = 0x0451;
    /** Буква «е», на которую заменяется «ё». */
    private static final int YE = 0x0435;
    /** Предел числа различных исходных текстов в одном подготовленном поиске. */
    static final int MAX_CACHED_TEXTS = 512;
    /** Предел суммы длин исходных строк в кэше, в кодовых единицах UTF-16. */
    static final int MAX_CACHED_CHARACTERS = 65_536;

    private SearchText() {
    }

    /**
     * Нормализует текст поиска без контекстного преобразования регистра и без Unicode NFC/NFKC.
     * Все Character.isWhitespace/isSpaceChar схлопываются в обычный пробел; края обрезаются.
     * @param text исходный текст; null означает пустой
     * @return нижний регистр с заменой «ё» на «е» и прежней обработкой пробелов
     */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && !result.isEmpty()) result.append(' ');
            pendingSpace = false;
            int lower = Character.toLowerCase(cp);
            result.appendCodePoint(lower == YO ? YE : lower);
        }
        return result.toString();
    }

    /**
     * Нормализует запрос один раз и готовит поиск подстроки с локальным ограниченным LRU-кэшем результатов.
     * Ключ - фактический исходный текст, поэтому одинаковые поля разных событий разделяют ответ,
     * а изменённая заметка/название/категория получают самостоятельный результат.
     * @param query запрос; null и пробельный запрос принимают любой текст
     * @return предикат для одного прохода в одном потоке; для нового запроса создаётся новый экземпляр
     */
    public static Predicate<String> prepare(String query) {
        return new Prepared(normalize(query));
    }

    /** Локальный поисковый снимок: нормализованный запрос и ограниченная память только о текстах полей. */
    static final class Prepared implements Predicate<String> {
        private final String needle;
        private final Map<String, Boolean> matches = new LinkedHashMap<>(256, 0.75f, true);
        private int cachedCharacters;

        /** Принимает уже нормализованный запрос, чтобы проход не выполнял его нормализацию повторно. */
        private Prepared(String needle) {
            this.needle = needle;
        }

        /**
         * Проверяет фактический текст поля. Пустой запрос не нормализует поля и не заполняет кэш.
         * Строки длиннее суммарного лимита проверяются без сохранения; вытеснение меняет только стоимость поиска.
         * @param text текст поля или null
         * @return содержит ли нормализованное поле подготовленную подстроку
         */
        @Override
        public boolean test(String text) {
            if (needle.isEmpty()) return true;
            if (text == null || text.isEmpty()) return false;
            Boolean known = matches.get(text);
            if (known != null) return known;
            boolean found = normalize(text).contains(needle);
            if (text.length() > MAX_CACHED_CHARACTERS) return found;
            while (matches.size() >= MAX_CACHED_TEXTS
                    || cachedCharacters + text.length() > MAX_CACHED_CHARACTERS) {
                var oldest = matches.entrySet().iterator();
                cachedCharacters -= oldest.next().getKey().length();
                oldest.remove();
            }
            matches.put(text, found);
            cachedCharacters += text.length();
            return found;
        }

        /** @return фактическое число удерживаемых различных текстов, для проверки ограничения памяти */
        int cachedTextCount() {
            return matches.size();
        }

        /** @return фактическая суммарная длина ключей UTF-16, для проверки ограничения памяти */
        int cachedCharacterCount() {
            return cachedCharacters;
        }
    }
}
