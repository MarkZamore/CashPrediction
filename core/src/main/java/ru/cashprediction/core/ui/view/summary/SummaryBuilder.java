package ru.cashprediction.core.ui.view.summary;

import ru.cashprediction.core.app.AppState;

/**
 * Построение панели сводки из состояния (спецификация v2, §5.1).
 *
 * <p>Опорные даты: anchor = max(начало плана, сегодня), end = конец прогноза. Цвет значения: сумма &lt; 0 →
 * {@code expense}; подушка &gt; 0 и сумма ниже подушки → {@code warn}; иначе {@code text.primary}. Карточки m1–m12
 * за горизонтом показывают «—» и «за горизонтом плана»; «Первый минус», «Средний итог/мес» и «Цель» — по таблице
 * §5.1. Если прогноз не рассчитан — карточек нет, текст {@code summary.unavailable}.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class SummaryBuilder {

    private SummaryBuilder() {
    }

    /**
     * Строит модель панели.
     *
     * @param state состояние приложения
     * @return модель сводки
     */
    public static SummaryModel build(AppState state) {
        throw new UnsupportedOperationException("S1: core-views - SummaryBuilder.build");
    }
}
