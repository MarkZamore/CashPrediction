package ru.cashprediction.core.ui.command;

import java.util.Optional;

/**
 * Полный список команд интерфейса (спецификация v2, §3 меню, §4 тулбар, §5 контекстные меню и пустые состояния, §7).
 *
 * <p>Идентификатор {@link #id()} совпадает с id спецификации ({@code file.new}, {@code view.period.M3}) и служит
 * основой ключей каталога текстов: {@code menu.<id>} — метка, {@code menu.<id>.tip} — подсказка
 * ({@link #menuKey()}, {@link #tipKey()}); для контекстных меню — {@code ctx.<цель>.<id>} (спецификация §8,
 * {@code MenuModels}; цель — {@code ContextTarget.kind()}). Один и тот же {@code CommandId}
 * используется меню, тулбаром, контекстными меню, горячими клавишами и сценариями самотеста; доступность
 * вычисляет одна таблица {@link CommandAvailability}.</p>
 *
 * <p><b>Узлы без собственной команды.</b> Подменю «Недавние» ({@code file.recent}), «Что-если» ({@code tools.whatIf}),
 * «Симулировать сбой» ({@code recovery.simulate}) и кнопки-меню тулбара ({@code tb.period}, {@code tb.whatIf})
 * — контейнеры: их id есть у узлов {@code MenuNode}/{@code ToolbarNode}, но не здесь.</p>
 *
 * <p><b>Повторное использование команд.</b> Кнопки пустых состояний таблицы (§5.2) вызывают
 * {@link #EDIT_ADD_INCOME}, {@link #EDIT_ADD_EXPENSE}, {@link #FILE_SAMPLE} и {@link #FILTER_CLEAR}; основная часть
 * {@code tb.add} — {@link #EDIT_ADD_INCOME}; переключатели тулбара — {@link #VIEW_TABLE}/{@link #VIEW_CHART};
 * «↶», «↷», «Сохранить» — {@link #EDIT_UNDO}, {@link #EDIT_REDO}, {@link #FILE_SAVE}; флажки в контекстных меню
 * итога, карточки и графика — соответствующие {@code view.flag.*}; «Калькулятор цели…» карточки —
 * {@link #TOOLS_GOAL}; «Сохранить график PNG…» графика — {@link #FILE_SAVE_PNG}.</p>
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum CommandId {

    // ---------------------------------------------------------------- §3.1 Файл
    /** «Новый план…» — мастер (после вопроса о несохранённых изменениях). */
    FILE_NEW("file.new"),
    /** «Открыть…» — список планов папки CashMemory или выбранной папки. */
    FILE_OPEN("file.open"),
    /** «Открыть из файла…» — выбор файла .md из любой папки. */
    FILE_OPEN_FILE("file.openFile"),
    /** «Открыть пример» — {@code SamplePlan}. */
    FILE_SAMPLE("file.sample"),
    /** Пункт подменю «Недавние»: {@code CommandArgs.value} — полный путь, {@code CommandArgs.key} — номер пункта. */
    FILE_RECENT_OPEN("file.recent.open"),
    /** «Сохранить». */
    FILE_SAVE("file.save"),
    /** «Сохранить как…». */
    FILE_SAVE_AS("file.saveAs"),
    /** «Переименовать…». */
    FILE_RENAME("file.rename"),
    /** Флажок «Автосохранение». */
    FILE_AUTOSAVE("file.autosave"),
    /** «Экспорт CSV…». */
    FILE_EXPORT_CSV("file.exportCsv"),
    /** «Сохранить график PNG…». */
    FILE_SAVE_PNG("file.savePng"),
    /** «Папка CashMemory…». */
    FILE_CASH_MEMORY("file.cashMemory"),
    /** «Выход» (тот же путь, что крестик и Alt+F4, §6.32). */
    FILE_EXIT("file.exit"),

    // ---------------------------------------------------------------- §3.2 Правка
    /** «Добавить доход…» — редактор правила с видом «Доход». */
    EDIT_ADD_INCOME("edit.addIncome"),
    /** «Добавить расход…» — редактор правила с видом «Расход». */
    EDIT_ADD_EXPENSE("edit.addExpense"),
    /** «Разовая операция…» — вид «Расход»; {@code CommandArgs.date} — дата по умолчанию. */
    EDIT_ADD_ONE_TIME("edit.addOneTime"),
    /** «Изменить…» по виду выделенной строки: RULE, ONE_TIME, START → параметры, WHAT_IF → подсказка. */
    EDIT_EDIT("edit.edit"),
    /** «Удалить…» операции выделенной строки. */
    EDIT_DELETE("edit.delete"),
    /** «Скорректировать событие…». */
    EDIT_ADJUST("edit.adjust"),
    /** «Пропустить событие». */
    EDIT_SKIP("edit.skip"),
    /** «Вернуть как по правилу». */
    EDIT_RESET("edit.reset"),
    /** «Отменить» / «Отменить: {0}». */
    EDIT_UNDO("edit.undo"),
    /** «Повторить» / «Повторить: {0}». */
    EDIT_REDO("edit.redo"),
    /** «Параметры плана…». */
    EDIT_PLAN_SETTINGS("edit.planSettings"),
    /** «Актуализировать на сегодня…». */
    EDIT_ACTUALIZE("edit.actualize"),
    /** «Сверить баланс…». */
    EDIT_RECONCILE("edit.reconcile"),

    // ---------------------------------------------------------------- §3.3 Вид
    /** Радио «Таблица». */
    VIEW_TABLE("view.table"),
    /** Радио «График». */
    VIEW_CHART("view.chart"),
    /** Флажок «Доходы». */
    VIEW_FLAG_SHOW_INCOME("view.flag.showIncome"),
    /** Флажок «Расходы». */
    VIEW_FLAG_SHOW_EXPENSE("view.flag.showExpense"),
    /** Флажок «Разовые операции». */
    VIEW_FLAG_SHOW_ONE_TIME("view.flag.showOneTime"),
    /** Флажок «Пропущенные события». */
    VIEW_FLAG_SHOW_SKIPPED("view.flag.showSkipped"),
    /** Флажок «Итоги по месяцам». */
    VIEW_FLAG_MONTH_TOTALS("view.flag.monthTotals"),
    /** Флажок «Маркеры событий на графике». */
    VIEW_FLAG_CHART_MARKERS("view.flag.chartMarkers"),
    /** Флажок «Столбцы итогов месяцев на графике». */
    VIEW_FLAG_CHART_BARS("view.flag.chartBars"),
    /** Флажок «Панель сводки». */
    VIEW_FLAG_SUMMARY_PANEL("view.flag.summaryPanel"),
    /** Радио «Период: 3 месяца». */
    VIEW_PERIOD_M3("view.period.M3"),
    /** Радио «Период: 6 месяцев». */
    VIEW_PERIOD_M6("view.period.M6"),
    /** Радио «Период: 12 месяцев». */
    VIEW_PERIOD_M12("view.period.M12"),
    /** Радио «Период: 24 месяца». */
    VIEW_PERIOD_M24("view.period.M24"),
    /** Радио «Период: весь горизонт». */
    VIEW_PERIOD_ALL("view.period.ALL"),
    /** Слайдер «Горизонт плана: {N месяцев}»; значение приходит через {@code UiIntents.sliderCommit}. */
    VIEW_HORIZON_SLIDER("view.horizonSlider"),
    /** «Горизонт: другое число месяцев…». */
    VIEW_HORIZON_MONTHS("view.horizonMonths"),
    /** «Перейти к фильтру». */
    VIEW_FOCUS_FILTER("view.focusFilter"),

    // ---------------------------------------------------------------- §3.4 Инструменты
    /** «Калькулятор цели…» (один экземпляр, повторный вызов поднимает окно). */
    TOOLS_GOAL("tools.goal"),
    /** Флажок «Доходы −10 %». */
    WHAT_IF_INCOME("whatIf.income"),
    /** Флажок «Расходы +10 %». */
    WHAT_IF_EXPENSE("whatIf.expense"),
    /** Спиннер «Откладывать доп. в месяц»; значение приходит через {@code UiIntents.spinnerCommit}. */
    WHAT_IF_EXTRA("whatIf.extra"),
    /** «Применить к плану…». */
    WHAT_IF_APPLY("whatIf.apply"),
    /** «Сбросить «что-если»». */
    WHAT_IF_RESET("whatIf.reset"),
    /** «Проверить план…». */
    TOOLS_VALIDATE("tools.validate"),
    /** «Очистить неиспользуемые корректировки». */
    TOOLS_CLEANUP("tools.cleanup"),
    /** «Валюта…». */
    TOOLS_CURRENCY("tools.currency"),

    // ---------------------------------------------------------------- §3.5 Восстановление
    /** Радио «Хранилище по умолчанию: реестр Windows» (FX, Swing). */
    RECOVERY_STORE_REGISTRY("recovery.store.registry"),
    /** Радио «Хранилище по умолчанию: XML-файл» (FX, Swing). */
    RECOVERY_STORE_XML("recovery.store.xml"),
    /** Отмеченное и отключённое радио «Хранилище: сервер (web-session.md)» (только web, §10 №1). */
    RECOVERY_STORE_SERVER("recovery.store.server"),
    /** «Сделать снимок сейчас». */
    RECOVERY_SNAPSHOT_NOW("recovery.snapshotNow"),
    /** «Показать последний снимок…». */
    RECOVERY_SHOW_LAST("recovery.showLast"),
    /** «Очистить снимки…». */
    RECOVERY_CLEAR("recovery.clear"),
    /** «Аварийное завершение процесса…». */
    RECOVERY_SIMULATE_HALT("recovery.simulate.halt"),
    /** «Необработанное исключение». */
    RECOVERY_SIMULATE_EXCEPTION("recovery.simulate.exception"),

    // ---------------------------------------------------------------- §3.6 Справка
    /** «О программе». */
    HELP_ABOUT("help.about"),
    /** «Горячие клавиши…». */
    HELP_HOTKEYS("help.hotkeys"),
    /** «Формат файла .md…». */
    HELP_FORMAT("help.format"),

    // ---------------------------------------------------------------- §4 тулбар, §5.2 пустые состояния, §7
    /** Кнопка «✕» поля фильтра, Esc в поле фильтра, кнопка «Очистить фильтр» пустого состояния. */
    FILTER_CLEAR("filter.clear"),
    /** Enter в поле фильтра: применить текст без задержки и перевести фокус в таблицу (§4 п. 8). */
    FILTER_FOCUS_TABLE("filter.focusTable"),

    // ---------------------------------------------------------------- §5.2 контекстное меню строки события
    /** «Изменить…» (для START «Параметры плана…»); {@code CommandArgs.rowId}. */
    ROW_EDIT("row.edit"),
    /** «Быстрая правка суммы…». */
    ROW_QUICK_EDIT("row.quickEdit"),
    /** «Скорректировать событие…». */
    ROW_ADJUST("row.adjust"),
    /** «Пропустить событие». */
    ROW_SKIP("row.skip"),
    /** «Вернуть как по правилу». */
    ROW_RESET("row.reset"),
    /** «Добавить разовую на dd.MM.yyyy…» (вид «Расход»); {@code CommandArgs.date}. */
    ROW_ADD_ONE_TIME("row.addOneTime"),
    /** «Перейти к правилу…» — редактор правила. */
    ROW_GO_TO_RULE("row.goToRule"),
    /** «Отключить правило» без подтверждения. */
    ROW_DISABLE_RULE("row.disableRule"),
    /** «Копировать строку». */
    ROW_COPY("row.copy"),
    /** «Удалить…». */
    ROW_DELETE("row.delete"),

    // ---------------------------------------------------------------- §5.2 итог месяца и PAST_HEADER
    /** «Копировать итог месяца». */
    TOTAL_COPY("total.copy"),
    /** Переключить группу «Прошедшие события»: щелчок, Enter, Пробел, пункт «Показать/Свернуть прошедшие события». */
    PAST_TOGGLE("past.toggle"),

    // ---------------------------------------------------------------- §5.1 карточка сводки
    /** «Показать в таблице с dd.MM.yyyy», двойной щелчок по карточке; {@code CommandArgs.cardId}, {@code date}. */
    CARD_SHOW_IN_TABLE("card.showInTable"),
    /** «Копировать значение». */
    CARD_COPY_VALUE("card.copyValue"),

    // ---------------------------------------------------------------- §5.3 график
    /** «Показать в таблице с dd.MM.yyyy», двойной щелчок по графику; {@code CommandArgs.date}. */
    CHART_SHOW_IN_TABLE("chart.showInTable"),
    /** «Добавить разовую на dd.MM.yyyy…» / «Добавить разовую…». */
    CHART_ADD_ONE_TIME("chart.addOneTime"),

    // ---------------------------------------------------------------- §7 клавиши, которые выполняет сам инструмент
    /** F10 / Alt: перевести фокус в строку меню ({@code UiPort.focus(FocusTarget.MENU_BAR)}). */
    UI_MENU_BAR("ui.menuBar"),
    /**
     * Shift+F10 / Menu: контекстное меню выделенной строки (область TABLE) или карточки в фокусе (область CARD,
     * id карточки — {@code focusId} из {@code UiIntents.key}). Контроллер строит меню {@code MenuModels.contextMenu}
     * и вызывает {@code UiPort.showContextMenu(target, items)}; клиент показывает то же меню, что по правой кнопке.
     */
    UI_CONTEXT_MENU("ui.contextMenu"),

    // ---------------------------------------------------------------- §6.3 контекстное меню предпросмотра дат
    /**
     * «Скорректировать эту дату…» в списке предпросмотра редактора правила: источник {@code InvokeSource.FORM},
     * {@code CommandArgs.key} — id окна редактора, {@code CommandArgs.value} — номер элемента списка. Контроллер
     * передаёт его в {@code FormSession.previewSelected(index, true)} этого окна (открывается ADJUSTMENT_EDITOR,
     * владелец — редактор). Модальный редактор — верхнее окно, поэтому правило модальности команду пропускает.
     */
    PREVIEW_ADJUST("preview.adjust");

    private final String id;

    CommandId(String id) {
        this.id = id;
    }

    /** @return id спецификации, например {@code view.period.M3} */
    public String id() {
        return id;
    }

    /** @return ключ метки в каталоге: {@code menu.<id>} */
    public String menuKey() {
        return "menu." + id;
    }

    /** @return ключ подсказки в каталоге: {@code menu.<id>.tip} */
    public String tipKey() {
        return "menu." + id + ".tip";
    }

    /**
     * Ищет команду по id спецификации (для web-протокола и сценариев самотеста).
     *
     * @param id id, например {@code file.save}
     * @return команда или пусто
     */
    public static Optional<CommandId> byId(String id) {
        for (CommandId command : values()) {
            if (command.id.equals(id)) {
                return Optional.of(command);
            }
        }
        return Optional.empty();
    }
}
