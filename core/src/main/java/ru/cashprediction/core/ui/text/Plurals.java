package ru.cashprediction.core.ui.text;

import ru.cashprediction.core.util.RuText;

/**
 * Числительные с существительным в нужной форме по формам из каталога текстов.
 *
 * <p>Формы слова хранятся одним ключом каталога через вертикальную черту: {@code plural.month=месяц|месяца|месяцев}
 * (для 1, для 2–4, для 5–20). Правило выбора формы — {@link RuText#plural(long, String, String, String)}.
 * Тест каталога проверяет, что у каждого ключа {@code plural.*} ровно три формы.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class Plurals {

    /** Ключ форм слова «месяц». */
    public static final String MONTH = "plural.month";

    /** Ключ форм слова «год». */
    public static final String YEAR = "plural.year";

    private Plurals() {
    }

    /**
     * Форма слова для числа: {@code form(MONTH, 5)} = «месяцев».
     *
     * @param key ключ каталога {@code plural.*}
     * @param n   число
     * @return форма слова; если в каталоге не три формы — текст ключа как есть
     */
    public static String form(String key, long n) {
        String forms = UiText.get(key);
        String[] parts = forms.split("\\|", -1);
        return parts.length == 3 ? RuText.plural(n, parts[0], parts[1], parts[2]) : forms;
    }

    /**
     * Число со словом: {@code count(MONTH, 12)} = «12 месяцев».
     *
     * @param key ключ каталога {@code plural.*}
     * @param n   число
     * @return «число форма»
     */
    public static String count(String key, long n) {
        return n + " " + form(key, n);
    }
}
