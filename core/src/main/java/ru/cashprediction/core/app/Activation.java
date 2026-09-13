package ru.cashprediction.core.app;

/**
 * Каким действием мыши активирована строка таблицы ({@code UiIntents.activateRow}; спецификация v2, §5.2
 * «Мышь и клавиатура»). Клавиши Enter и Пробел приходят не сюда, а через {@code UiIntents.key}.
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum Activation {

    /** Одиночный щелчок: переключает группу PAST_HEADER; для остальных строк ничего не делает. */
    CLICK,
    /**
     * Двойной щелчок: по непустой сумме «Доход»/«Расход» строки RULE (не пропущенной) — быстрая правка; по другой
     * ячейке START/RULE/ONE_TIME/WHAT_IF — {@code edit.edit}; по итогу и PAST_HEADER — ничего.
     */
    DOUBLE_CLICK
}
