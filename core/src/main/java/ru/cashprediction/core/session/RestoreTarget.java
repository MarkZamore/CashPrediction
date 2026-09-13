package ru.cashprediction.core.session;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Главное окно клиента с точки зрения восстановления сессии; реализуется контроллером клиента.
 *
 * <p>{@link RestoreCoordinator} вызывает методы строго в порядке: {@link #loadPlan},
 * {@link #applyMain}, {@link #showMainWindow}, затем (после открытия всех окон)
 * {@link #selectRow}. Все вызовы — в UI-потоке.</p>
 */
public interface RestoreTarget {

    /**
     * Загружает план: при {@code plan.dirty()} разбирает Markdown из снимка, иначе читает файл
     * {@code planPath} из CashMemory.
     *
     * <p>Исключение означает «план не загружен». Если при этом {@code plan.dirty()}, координатор не запускает
     * запись сессии, чтобы не затереть единственную копию несохранённого текста (см. {@link RestoreCoordinator}).
     * Поправимые проблемы (файл не найден, открыт пустой план) нужно сообщать через {@code warn}, а не исключением.</p>
     *
     * @param plan     состояние плана из снимка
     * @param planPath путь к файлу плана относительно CashMemory; пустая строка — план не был открыт
     * @param warn     приёмник предупреждений для отчёта восстановления (например, «файл плана не найден»)
     */
    void loadPlan(PlanState plan, String planPath, Consumer<String> warn);

    /**
     * Применяет геометрию, вид, период и фильтры главного окна (геометрию нужно прижать к экранам).
     *
     * @param main состояние главного окна
     */
    void applyMain(MainWindowState main);

    /**
     * Показывает главное окно.
     */
    void showMainWindow();

    /**
     * Выделяет строку прогноза и переводит на неё фокус.
     *
     * @param rowId идентификатор строки, например {@code r2@2026-10-01}
     */
    void selectRow(String rowId);

    /**
     * Идентификаторы объектов загруженного плана, на которые могут ссылаться окна.
     *
     * @return идентификаторы регулярных операций ({@code r1}...) и разовых операций ({@code t1}...)
     */
    Set<String> existingTargetIds();
}
