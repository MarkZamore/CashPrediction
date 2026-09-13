package ru.cashprediction.swing.selftest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор строк сценария самотеста: слова через пробелы, значения с пробелами — в двойных кавычках
 * ({@code fill w1 title="Моя аренда" amount=45000,00}). Кавычки в слово не входят; {@code \"} внутри кавычек —
 * сама кавычка.
 *
 * <p>Класс без состояния.</p>
 */
public final class SelfTestTokenizer {

    private SelfTestTokenizer() {
    }

    /**
     * Делит строку на слова.
     *
     * @param line строка сценария
     * @return слова (без кавычек)
     * @throws IllegalArgumentException если кавычка не закрыта
     */
    public static List<String> tokens(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean hasToken = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes && c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                current.append('"');
                i++;
            } else if (c == '"') {
                inQuotes = !inQuotes;
                hasToken = true;
            } else if (!inQuotes && Character.isWhitespace(c)) {
                if (hasToken) {
                    result.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
            } else {
                current.append(c);
                hasToken = true;
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("не закрыта кавычка");
        }
        if (hasToken) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * Разбирает пары {@code ключ=значение}.
     *
     * @param tokens слова
     * @param from   индекс первого слова с парой
     * @return ключ → значение в порядке сценария
     * @throws IllegalArgumentException если в слове нет {@code =}
     */
    public static Map<String, String> pairs(List<String> tokens, int from) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = from; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int eq = token.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("ожидалось ключ=значение, получено «" + token + "»");
            }
            result.put(token.substring(0, eq), token.substring(eq + 1));
        }
        return result;
    }
}
