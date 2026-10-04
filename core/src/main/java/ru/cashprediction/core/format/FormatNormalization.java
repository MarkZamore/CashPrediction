package ru.cashprediction.core.format;

/**
 * Нормализация слов грамматики для сравнения без зависимости от парсеров и хранилищ.
 * Применяется к настройкам вида и значениям Markdown-плана с одинаковой семантикой.
 */
public final class FormatNormalization {
    /** Буква, заменяемая при сравнении слов грамматики. */
    private static final int YO = FormatWords.get("common.char.yo").codePointAt(0);
    /** Замена буквы для сравнения. */
    private static final int YE = FormatWords.get("common.char.ye").codePointAt(0);

    /** Утилита не имеет экземпляров и изменяемого состояния. */
    private FormatNormalization() { }

    /**
     * Схлопывает пробельные символы, обрезает края и приводит кодовые точки к нижнему регистру.
     * Буквы из common.char.yo и common.char.ye считаются равнозначными.
     * @param text исходный текст; null означает пустой текст
     * @return нормализованная строка
     */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && !sb.isEmpty()) sb.append(' ');
            pendingSpace = false;
            int lower = Character.toLowerCase(cp);
            sb.appendCodePoint(lower == YO ? YE : lower);
        }
        return sb.toString();
    }
}
