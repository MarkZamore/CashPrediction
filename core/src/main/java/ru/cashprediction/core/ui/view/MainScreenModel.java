package ru.cashprediction.core.ui.view;

import java.util.Objects;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.ui.menu.MenuBarModel;
import ru.cashprediction.core.ui.menu.ToolbarModel;
import ru.cashprediction.core.ui.view.chart.ChartModel;
import ru.cashprediction.core.ui.view.status.StatusModel;
import ru.cashprediction.core.ui.view.summary.SummaryModel;
import ru.cashprediction.core.ui.view.table.TableModel;

/**
 * Полная модель главного окна (спецификация v2, §2; архитектура §3.4): области сверху вниз — меню, тулбар,
 * сводка, центр (таблица или график), строка состояния. Клиент ничего не решает: он рисует эту модель.
 *
 * @param revision    монотонный номер модели; web использует его в ответах {@code screen} и запросах {@code rows}
 * @param windowTitle заголовок окна (web: {@code document.title})
 * @param menuBar     строка меню
 * @param toolbar     тулбар
 * @param summary     панель сводки
 * @param table       ленивая модель таблицы
 * @param chart       модель графика
 * @param status      строка состояния
 * @param mode        что показывает центр окна
 */
public record MainScreenModel(long revision, String windowTitle, MenuBarModel menuBar, ToolbarModel toolbar,
                              SummaryModel summary, TableModel table, ChartModel chart, StatusModel status,
                              ViewMode mode) {

    /** Проверяет обязательные поля. */
    public MainScreenModel {
        windowTitle = Objects.requireNonNullElse(windowTitle, "");
        Objects.requireNonNull(menuBar, "menuBar");
        Objects.requireNonNull(toolbar, "toolbar");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(chart, "chart");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(mode, "mode");
    }
}
