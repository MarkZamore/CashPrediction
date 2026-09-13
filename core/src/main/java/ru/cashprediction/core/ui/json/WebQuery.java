package ru.cashprediction.core.ui.json;

import java.time.LocalDate;
import java.time.YearMonth;
import ru.cashprediction.core.ui.menu.ContextTarget;

/**
 * Запрос web-протокола {@code POST /api/ui/query {type, …}} → {@code {result}} или {@code {stale:true, rev}}
 * (архитектура §5). Запросы не меняют состояние и отвечают и при открытом модальном окне.
 */
public sealed interface WebQuery permits WebQuery.ContextMenu, WebQuery.Tooltip, WebQuery.Rows, WebQuery.Chart,
        WebQuery.ChartHover, WebQuery.DayCard, WebQuery.Sparkline, WebQuery.Calendar {

    /** @return значение поля {@code type} в JSON */
    String type();

    /**
     * {@code contextMenu{target}} → список {@code MenuNode}.
     *
     * @param target объект меню ({@code kind} + поля цели)
     */
    record ContextMenu(ContextTarget target) implements WebQuery {
        @Override
        public String type() {
            return "contextMenu";
        }
    }

    /**
     * {@code tooltip{rev, index, columnId}} → текст.
     *
     * @param rev      ревизия таблицы
     * @param index    индекс строки
     * @param columnId id колонки
     */
    record Tooltip(long rev, int index, String columnId) implements WebQuery {
        @Override
        public String type() {
            return "tooltip";
        }
    }

    /**
     * {@code rows{rev, from, count ≤ 300}} → {@code TableRowView[]}; устаревшая ревизия — {@code stale}.
     *
     * @param rev   ревизия таблицы
     * @param from  первый индекс
     * @param count сколько строк (не больше 300)
     */
    record Rows(long rev, int from, int count) implements WebQuery {
        @Override
        public String type() {
            return "rows";
        }
    }

    /**
     * {@code chartScene{rev, w, h}} → {@code ChartScene}.
     *
     * @param rev ревизия графика
     * @param w   ширина
     * @param h   высота
     */
    record Chart(long rev, double w, double h) implements WebQuery {
        @Override
        public String type() {
            return "chartScene";
        }
    }

    /**
     * {@code chartHover{rev, x, y, w, h}} → {@code ChartHover} или {@code null} ({@code UiIntents.chartHover}):
     * пунктирная вертикаль, точка на линии баланса и карточка дня у указателя (§5.3 «Наведение»). Координаты точки
     * считает ядро, вкладка их только рисует (правило R2); устаревшая ревизия — {@code stale}.
     *
     * @param rev ревизия графика
     * @param x   координата x указателя
     * @param y   координата y указателя
     * @param w   ширина области рисования
     * @param h   высота области рисования
     */
    record ChartHover(long rev, double x, double y, double w, double h) implements WebQuery {
        @Override
        public String type() {
            return "chartHover";
        }
    }

    /**
     * {@code dayCard{date}} → {@code DayCardModel}.
     *
     * @param date дата
     */
    record DayCard(LocalDate date) implements WebQuery {
        @Override
        public String type() {
            return "dayCard";
        }
    }

    /**
     * {@code sparkline{cardId}} → {@code SparklineModel}.
     *
     * @param cardId id карточки
     */
    record Sparkline(String cardId) implements WebQuery {
        @Override
        public String type() {
            return "sparkline";
        }
    }

    /**
     * {@code calendar{month, selected}} → {@code CalendarModel}.
     *
     * @param month    месяц ({@code 2026-10})
     * @param selected выбранная дата или {@code null}
     */
    record Calendar(YearMonth month, LocalDate selected) implements WebQuery {
        @Override
        public String type() {
            return "calendar";
        }
    }
}
