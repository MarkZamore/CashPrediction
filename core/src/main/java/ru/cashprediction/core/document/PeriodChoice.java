package ru.cashprediction.core.document;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.cashprediction.core.markdown.RuFormats;

/**
 * Период отображения: какая часть горизонта прогноза видна в таблице и на графике.
 *
 * <p>Период — только настройка вида. Он не пишется в файл плана и не меняет сам прогноз
 * (в отличие от горизонта {@code Plan.horizon}); реальная длина видимой части не больше горизонта.</p>
 */
public enum PeriodChoice {
    /** Ближайшие 3 месяца. */
    M3(3, "3 месяца"),
    /** Ближайшие 6 месяцев. */
    M6(6, "6 месяцев"),
    /** Ближайшие 12 месяцев. */
    M12(12, "12 месяцев"),
    /** Ближайшие 24 месяца. */
    M24(24, "24 месяца"),
    /** Весь горизонт плана. */
    ALL(0, "Весь горизонт");

    /** Ведущее число в тексте периода: «12», «12 мес». */
    private static final Pattern LEADING_NUMBER = Pattern.compile("(\\d{1,4})(?:\\D.*)?");

    private final int months;
    private final String label;

    PeriodChoice(int months, String label) {
        this.months = months;
        this.label = label;
    }

    /** @return число месяцев периода; 0 для {@link #ALL} */
    public int months() {
        return months;
    }

    /** @return подпись для меню и файла настроек: «3 месяца» ... «Весь горизонт» */
    public String label() {
        return label;
    }

    /**
     * Распознаёт период по подписи, имени константы или числу месяцев.
     *
     * @param text «12 месяцев», «M12», «12», «весь горизонт», «all»
     * @return период или пустое значение, если текст не распознан
     */
    public static Optional<PeriodChoice> parse(String text) {
        String t = RuFormats.normalize(text);
        for (PeriodChoice choice : values()) {
            if (t.equals(RuFormats.normalize(choice.label)) || t.equals(choice.name().toLowerCase(Locale.ROOT))) {
                return Optional.of(choice);
            }
        }
        if (t.equals("весь") || t.equals("все") || t.equals("all")) {
            return Optional.of(ALL);
        }
        Matcher m = LEADING_NUMBER.matcher(t);
        if (m.matches()) {
            int value = Integer.parseInt(m.group(1));
            for (PeriodChoice choice : values()) {
                if (choice.months == value && choice != ALL) {
                    return Optional.of(choice);
                }
            }
        }
        return Optional.empty();
    }
}
