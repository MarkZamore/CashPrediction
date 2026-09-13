package ru.cashprediction.core.document;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.markdown.RuFormats;

/**
 * Период отображения: какая часть горизонта прогноза видна в таблице и на графике.
 *
 * <p>Период — только настройка вида. Он не пишется в файл плана и не меняет сам прогноз
 * (в отличие от горизонта {@code Plan.horizon}); реальная длина видимой части не больше горизонта.</p>
 *
 * <p>Подпись {@link #label()} — слово грамматики {@code settings.md}, а не текст интерфейса: оно берётся из
 * нелокализуемого ресурса {@link FormatWords} ({@code settings.period.*}), чтобы файл настроек читался при
 * любом языке интерфейса.</p>
 */
public enum PeriodChoice {
    /** Ближайшие 3 месяца. */
    M3(3),
    /** Ближайшие 6 месяцев. */
    M6(6),
    /** Ближайшие 12 месяцев. */
    M12(12),
    /** Ближайшие 24 месяца. */
    M24(24),
    /** Весь горизонт плана. */
    ALL(0);

    /** Ведущее число в тексте периода: «12», «12 мес». */
    private static final Pattern LEADING_NUMBER = Pattern.compile("(\\d{1,4})(?:\\D.*)?");

    private final int months;

    PeriodChoice(int months) {
        this.months = months;
    }

    /** @return число месяцев периода; 0 для {@link #ALL} */
    public int months() {
        return months;
    }

    /** @return подпись для меню и файла настроек: «3 месяца» ... «Весь горизонт» */
    public String label() {
        // Слово ищется при каждом вызове, а не в конструкторе: ошибка ресурса не ломает загрузку перечисления.
        return switch (this) {
            case M3 -> FormatWords.get("settings.period.m3");
            case M6 -> FormatWords.get("settings.period.m6");
            case M12 -> FormatWords.get("settings.period.m12");
            case M24 -> FormatWords.get("settings.period.m24");
            case ALL -> FormatWords.get("settings.period.all");
        };
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
            if (t.equals(RuFormats.normalize(choice.label())) || t.equals(choice.name().toLowerCase(Locale.ROOT))) {
                return Optional.of(choice);
            }
        }
        // Короткие синонимы «весь»/«все» из ручной правки файла настроек; «all» — имя константы по-английски.
        // Слова ресурса нормализуются так же, как текст файла: регистр и «ё» в ресурсе не ломают чтение.
        if (t.equals(RuFormats.normalize(FormatWords.get("settings.period.all.alias1")))
                || t.equals(RuFormats.normalize(FormatWords.get("settings.period.all.alias2"))) || t.equals("all")) {
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
