package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.command.KeyChord;

/**
 * Команда сценария самотеста (архитектура §6.2): язык прежних самотестов FX/Swing плюс новые команды единого
 * интерфейса. Одна команда — одна строка сценария {@code *.cps}; {@code #} — комментарий; значения с пробелами — в
 * кавычках.
 */
public sealed interface SelfTestCommand {

    // ---------------------------------------------------------------- прежний язык FX/Swing

    /**
     * {@code wait <мс>}: пауза без блокировки потока интерфейса (выполняет {@code SelfTestRunner}).
     *
     * @param millis миллисекунды
     */
    record Wait(int millis) implements SelfTestCommand {
    }

    /** {@code sample}: как «Файл → Открыть пример». */
    record Sample() implements SelfTestCommand {
    }

    /**
     * {@code open <WindowType> [ключ=значение …]}: окно как по команде пользователя с этим контекстом.
     *
     * @param type    тип окна
     * @param context контекст
     */
    record Open(WindowType type, Map<String, String> context) implements SelfTestCommand {
        /** Проверяет поля и копирует карту. */
        public Open {
            Objects.requireNonNull(type, "type");
            context = context == null ? Map.of() : Map.copyOf(context);
        }
    }

    /**
     * {@code fill <wN|last> <поле>=<значение> …}: ввести значения в настоящие поля формы (канонические формы).
     *
     * @param window id окна или {@code last}
     * @param values значения по id поля
     */
    record Fill(String window, Map<String, String> values) implements SelfTestCommand {
        /** Проверяет поля и копирует карту. */
        public Fill {
            Objects.requireNonNull(window, "window");
            values = values == null ? Map.of() : Map.copyOf(values);
        }
    }

    /**
     * {@code ok <wN|last>}: кнопка по умолчанию формы.
     *
     * @param window id окна или {@code last}
     */
    record Ok(String window) implements SelfTestCommand {
    }

    /**
     * {@code cancel <wN|last>}: отмена формы.
     *
     * @param window id окна или {@code last}
     */
    record Cancel(String window) implements SelfTestCommand {
    }

    /**
     * {@code view TABLE|CHART}.
     *
     * @param mode режим
     */
    record View(ViewMode mode) implements SelfTestCommand {
    }

    /**
     * {@code period M3|M6|M12|M24|ALL}.
     *
     * @param period период
     */
    record Period(PeriodChoice period) implements SelfTestCommand {
    }

    /**
     * {@code filter <ключ>=true|false}: флажок вида или дополнительный ключ снимка.
     *
     * @param key   ключ ({@code showIncome}, {@code pastExpanded}, …)
     * @param value значение
     */
    record Filter(String key, boolean value) implements SelfTestCommand {
    }

    /**
     * {@code select <rowId>}: выделить строку таблицы.
     *
     * @param rowId id строки
     */
    record Select(String rowId) implements SelfTestCommand {
    }

    /**
     * {@code quickedit <rowId> <сумма>}: открыть быструю правку и ввести сумму, не подтверждая.
     *
     * @param rowId  id строки
     * @param amount текст суммы
     */
    record QuickEdit(String rowId, String amount) implements SelfTestCommand {
    }

    /** {@code save}: Ctrl+S. */
    record Save() implements SelfTestCommand {
    }

    /** {@code snapshot}: {@code recorder.saveNow()}. */
    record Snapshot() implements SelfTestCommand {
    }

    /**
     * {@code menus <путь>}: дерево меню, тулбара и контекстных меню (прежний язык; в едином интерфейсе входит в дамп).
     *
     * @param path файл
     */
    record Menus(String path) implements SelfTestCommand {
    }

    /**
     * {@code signal <путь>}: создать файл и ждать, пока его удалят (до 60 с), — синхронизация с внешним тестом.
     *
     * @param path файл-сигнал
     */
    record Signal(String path) implements SelfTestCommand {
    }

    /** {@code crash}: {@code Runtime.halt(3)} без сохранения. */
    record Crash() implements SelfTestCommand {
    }

    /** {@code throw}: необработанное исключение в потоке интерфейса. */
    record Throw() implements SelfTestCommand {
    }

    /** {@code exit}: корректный выход без вопросов. */
    record Exit() implements SelfTestCommand {
    }

    // ---------------------------------------------------------------- новые команды единого интерфейса

    /**
     * {@code today <ГГГГ-ММ-ДД>}: зафиксировать «сегодня» (обычно задаётся {@code --today}).
     *
     * @param date дата
     */
    record Today(LocalDate date) implements SelfTestCommand {
    }

    /**
     * {@code size <ширина> <высота>}: размер главного окна (web — размер окна браузера).
     *
     * @param width  ширина
     * @param height высота
     */
    record Size(int width, int height) implements SelfTestCommand {
    }

    /**
     * {@code menu <id или путь>}: выбрать пункт меню настоящим путём событий ({@code file.save} или «Файл/Сохранить»).
     *
     * @param idOrPath id узла или путь из текстов через «/»
     */
    record Menu(String idOrPath) implements SelfTestCommand {
    }

    /**
     * {@code click <id тулбара>}: нажать элемент тулбара ({@code tb.add}, {@code tb.add.menu:edit.addExpense}).
     *
     * @param toolbarId id элемента (для пункта выпадающего меню — {@code <id>.menu:<id пункта>})
     */
    record Click(String toolbarId) implements SelfTestCommand {
    }

    /**
     * {@code key <сочетание>}: нажать клавиши через диспетчер клиента ({@code Ctrl+S}, {@code Alt+Shift+1}).
     *
     * @param chord сочетание
     */
    record Key(KeyChord chord) implements SelfTestCommand {
    }

    /**
     * {@code dblclick <rowId> [columnId]}: двойной щелчок по ячейке таблицы.
     *
     * @param rowId    id строки
     * @param columnId id колонки или пустая строка (колонка «Операция»)
     */
    record DoubleClick(String rowId, String columnId) implements SelfTestCommand {
    }

    /**
     * {@code context <цель>}: открыть контекстное меню ({@code row:<rowId>}, {@code total:<rowId>},
     * {@code pastHeader}, {@code card:<id>}, {@code chart:<x>,<y>}, {@code preview:<wN>:<index>}); дамп включает меню,
     * затем оно закрывается.
     *
     * @param target цель
     */
    record Context(String target) implements SelfTestCommand {
    }

    /**
     * {@code hover <цель>}: навести указатель ({@code card:<id>}, {@code chart:<x>,<y>}, {@code menu:<id>}).
     *
     * @param target цель
     */
    record Hover(String target) implements SelfTestCommand {
    }

    /**
     * {@code field <заголовок окна> <подпись> <текст>}: ввести текст в поле по подписи, как пользователь.
     *
     * @param windowTitle заголовок окна
     * @param label       подпись поля
     * @param text        текст
     */
    record Field(String windowTitle, String label, String text) implements SelfTestCommand {
    }

    /**
     * {@code button <заголовок окна> <текст кнопки>}: нажать кнопку формы по тексту.
     *
     * @param windowTitle заголовок окна
     * @param label       текст кнопки
     */
    record Button(String windowTitle, String label) implements SelfTestCommand {
    }

    /**
     * {@code answer <текст кнопки>}: ответить на верхнее сообщение.
     *
     * @param buttonText текст кнопки
     */
    record Answer(String buttonText) implements SelfTestCommand {
    }

    /**
     * {@code chooser <путь>|cancel}: ответ на следующий запрос выбора файла или папки (заглушка выбора в самотесте).
     *
     * @param path путь или {@code null} для отмены
     */
    record Chooser(Path path) implements SelfTestCommand {
    }

    /**
     * {@code dump <шаг>}: записать дамп {@code <selftest-out>/<сценарий>/<шаг>.json} (прежний язык принимал путь —
     * тогда шаг = имя файла без расширения).
     *
     * @param step имя шага
     */
    record Dump(String step) implements SelfTestCommand {
    }

    /**
     * {@code shot <шаг>}: снимок экрана {@code <selftest-out>/<сценарий>/<шаг>.png} (1200×800, масштаб 1,0).
     *
     * @param step имя шага
     */
    record Shot(String step) implements SelfTestCommand {
    }

    // ---------------------------------------------------------------- взаимодействия, у которых нет обходного пути
    // Каждый драйвер обязан пройти настоящий путь событий виджета, описанный у команды: иначе клиенты придумали бы
    // разные обходы и сценарий перестал бы проверять одинаковость.

    /**
     * {@code slider <id узла> <значение>}: слайдер в меню ({@code view.horizonSlider}, s09). Драйвер открывает меню,
     * передвигает ползунок в значение и отпускает его: FX {@code Slider} ({@code valueChanging} → {@code false}) →
     * Swing {@code JSlider} ({@code getValueIsAdjusting()} → {@code false}) → Web {@code input type=range} (событие
     * {@code change}). План меняется одним {@code UiIntents.sliderCommit}.
     *
     * @param itemId id узла меню
     * @param value  значение шкалы
     */
    record SliderSet(String itemId, int value) implements SelfTestCommand {
        /** Проверяет поле. */
        public SliderSet {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /**
     * {@code spinner <id узла> <значение>}: спиннер в меню («Откладывать доп. в месяц», {@code whatIf.extra}, s09).
     * Драйвер открывает меню, вводит текст в редактор спиннера (FX {@code Spinner} → Swing {@code JSpinner} → Web
     * {@code input type=number}) и ждёт задержку применения ({@code awaitIdle}); {@code UiIntents.spinnerCommit}
     * вызывает сам виджет.
     *
     * @param itemId id узла меню
     * @param value  значение
     */
    record SpinnerSet(String itemId, long value) implements SelfTestCommand {
        /** Проверяет поле. */
        public SpinnerSet {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /**
     * {@code filtertype <текст>}: ввести текст в поле фильтра тулбара (s10). Драйвер ставит фокус в поле и вводит
     * текст как клавиатура (FX {@code TextField} → Swing {@code JTextField} → Web {@code input}); задержка 300 мс
     * выдерживается самим клиентом, затем {@code UiIntents.filterText}. Пустой текст — очистить поле.
     *
     * @param text текст
     */
    record FilterType(String text) implements SelfTestCommand {
        /** Заменяет {@code null}. */
        public FilterType {
            text = Objects.requireNonNullElse(text, "");
        }
    }

    /**
     * {@code rowclick <rowId> [columnId]}: одиночный щелчок мышью по ячейке таблицы (s11: переключение группы
     * PAST_HEADER). Драйвер отправляет событие мыши в ячейку виджета таблицы (FX {@code TableView} → Swing
     * {@code JTable} → Web строка виртуальной таблицы), а не вызывает {@code selectRow}: клиент сам вызывает
     * {@code UiIntents.selectRow} и {@code activateRow(…, CLICK)}.
     *
     * @param rowId    id строки
     * @param columnId id колонки или пустая строка (колонка «Операция»)
     */
    record RowClick(String rowId, String columnId) implements SelfTestCommand {
        /** Проверяет поля. */
        public RowClick {
            Objects.requireNonNull(rowId, "rowId");
            columnId = Objects.requireNonNullElse(columnId, "");
        }
    }

    /**
     * {@code pick <заголовок окна> <подпись> <текст элемента> [activate]}: выбрать элемент списка формы щелчком, а с
     * {@code activate} — двойным щелчком. Подходит для полей LIST (подпись поля; «Открыть план» §6.10, «Выбор файла»
     * §6.21) и списка предпросмотра (подпись — заголовок колонки «Ближайшие даты», §6.3). Путь событий: выбор в
     * виджете списка (FX {@code ListView} → Swing {@code JList} → Web {@code listbox}) → {@code FormSession.fieldChanged}
     * или {@code previewSelected}; двойной щелчок → {@code fieldActivated} или {@code previewSelected(index, true)}.
     *
     * @param windowTitle заголовок окна
     * @param label       подпись поля или колонки
     * @param itemText    текст элемента (точное совпадение)
     * @param activate    двойной щелчок вместо одиночного
     */
    record ListPick(String windowTitle, String label, String itemText, boolean activate) implements SelfTestCommand {
        /** Проверяет поля. */
        public ListPick {
            Objects.requireNonNull(windowTitle, "windowTitle");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(itemText, "itemText");
        }
    }

    /**
     * {@code enter <заголовок окна> <подпись>}: нажать Enter в однострочном поле формы (поле пути «Выбора файла»
     * §6.21, быстрая правка §5.6.1). Драйвер ставит фокус в виджет поля и отправляет ему нажатие Enter; клиент сам
     * передаёт текст ({@code fieldChanged}, committed) и {@code FormSession.fieldSubmitted}.
     *
     * @param windowTitle заголовок окна (у быстрой правки — подпись всплывающего окна)
     * @param label       подпись поля
     */
    record FieldEnter(String windowTitle, String label) implements SelfTestCommand {
        /** Проверяет поля. */
        public FieldEnter {
            Objects.requireNonNull(windowTitle, "windowTitle");
            Objects.requireNonNull(label, "label");
        }
    }
}
