package ru.cashprediction.core.session;

import java.util.Objects;

/**
 * Состояние плана в снимке: были ли несохранённые изменения и полный текст плана, если были.
 *
 * <p>Автосохранение плана по умолчанию выключено, поэтому несохранённые правки защищает только
 * снимок сессии: при {@code dirty = true} в нём лежит весь Markdown-текст плана, и восстановление
 * разбирает именно его, а не файл с диска (файл ещё не содержит правок).</p>
 *
 * <p>Запись неизменяема и потокобезопасна.</p>
 *
 * @param dirty    были ли несохранённые изменения
 * @param markdown полный текст плана в формате CashMemory; пустая строка, если {@code dirty = false}
 */
public record PlanState(boolean dirty, String markdown) {

    /** План без несохранённых изменений. */
    public static final PlanState CLEAN = new PlanState(false, "");

    /** Заменяет {@code null} на пустую строку. */
    public PlanState {
        markdown = Objects.requireNonNullElse(markdown, "");
    }

    /**
     * Создаёт состояние плана с несохранёнными изменениями.
     *
     * @param markdown полный текст плана
     * @return состояние {@code dirty = true}
     */
    public static PlanState dirty(String markdown) {
        return new PlanState(true, markdown);
    }
}
