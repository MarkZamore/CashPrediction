package ru.cashprediction.core.forecast;

/**
 * Отметки строки прогноза. Таблица показывает их значками (✎ → ⇄ зачёркивание Δ), CSV — словами.
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param shifted       дата сдвинута с выходного по политике правила (⇄)
 * @param amountChanged сумма изменена корректировкой «изменить» или «заменить» (✎)
 * @param moved         дата задана корректировкой «перенести» или «заменить» (→)
 * @param skipped       событие пропущено корректировкой «пропустить» и на баланс не влияет
 * @param past          дата строки раньше «сегодня»
 * @param whatIf        сумма или сама строка появились из-за режима «что-если» (Δ)
 */
public record Flags(boolean shifted, boolean amountChanged, boolean moved, boolean skipped, boolean past, boolean whatIf) {

    /** Строка без отметок. */
    public static final Flags NONE = new Flags(false, false, false, false, false, false);

    /**
     * @param value новое значение признака «прошедшая»
     * @return копия с другим признаком {@code past}
     */
    public Flags withPast(boolean value) {
        return new Flags(shifted, amountChanged, moved, skipped, value, whatIf);
    }

    /**
     * @param value новое значение признака «что-если»
     * @return копия с другим признаком {@code whatIf}
     */
    public Flags withWhatIf(boolean value) {
        return new Flags(shifted, amountChanged, moved, skipped, past, value);
    }

    /** @return {@code true}, если строка изменена корректировкой (сумма или дата) */
    public boolean adjusted() {
        return amountChanged || moved || skipped;
    }
}
