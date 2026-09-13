package ru.cashprediction.core.markdown;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Пункт списка «ключ: значение»: {@code - Валюта: ₽}.
 *
 * <p>Общий разборщик для секции «Параметры» файла плана и для {@code settings.md}: оба файла
 * хранят настройки одинаково, поэтому и правка их вручную подчиняется одним правилам.
 * При чтении допускаются маркеры {@code -}, {@code *}, {@code +}, любые пробелы вокруг ключа,
 * двоеточия и значения. Ключом считается текст до ПЕРВОГО двоеточия, так что значение
 * само может содержать двоеточие.</p>
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param key   ключ без обрамляющих пробелов, например «Валюта»
 * @param value значение без обрамляющих пробелов, может быть пустым
 */
public record ListItem(String key, String value) {

    /** Маркер списка, ключ до первого двоеточия, значение до конца строки. */
    private static final Pattern ITEM = Pattern.compile("^\\s*[-*+]\\s*([^:]*?)\\s*:\\s*(.*?)\\s*$");

    /** Проверяет обязательные поля и отбрасывает пробелы по краям. */
    public ListItem {
        Objects.requireNonNull(key, "key");
        key = key.strip();
        value = value == null ? "" : value.strip();
    }

    /**
     * Разбирает строку как пункт списка.
     *
     * @param line строка файла
     * @return пункт или пустое значение, если строка не является пунктом «- ключ: значение»
     */
    public static Optional<ListItem> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        Matcher m = ITEM.matcher(line);
        if (!m.matches() || m.group(1).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ListItem(m.group(1), m.group(2)));
    }

    /**
     * Выводит пункт в каноническом виде. Пустое значение выводится без завершающего пробела.
     *
     * @return строка {@code - ключ: значение}
     */
    public String format() {
        return value.isEmpty() ? "- " + key + ":" : "- " + key + ": " + value;
    }
}
