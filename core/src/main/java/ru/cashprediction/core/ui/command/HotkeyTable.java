package ru.cashprediction.core.ui.command;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.text.UiText;

/**
 * Единая таблица горячих клавиш (спецификация v2, §7; архитектура §3.2).
 *
 * <p>Нажатие ловит один диспетчер клиента до обработки инструментом (FX - фильтр событий сцены, Swing -
 * {@code KeyEventDispatcher}, web - {@code keydown} в фазе перехвата) и передаёт в {@code UiIntents.key}. Ускорители
 * в меню только отображаются, поэтому команда срабатывает ровно один раз.</p>
 *
 * <p><b>Содержимое:</b> для каждой строки §7 - сочетание Desktop и сочетание Web; оба работают во всех клиентах
 * (Alt+Shift+N/O/C/T/1/2 - и на десктопе), показывается сочетание своей платформы ({@link HotkeyBinding#shown()}).
 * Дополнительно Ctrl+Shift+Z повторяет; Esc в {@link FocusScope#FILTER} - {@code filter.clear}; Enter в
 * {@link FocusScope#FILTER} - {@code filter.focusTable}; Пробел в {@link FocusScope#TABLE} - {@code past.toggle}
 * (действует только на строке PAST_HEADER); Shift+F10 и Menu в {@link FocusScope#TABLE} и {@link FocusScope#CARD} -
 * {@code ui.contextMenu}; F10 и одиночный Alt ({@code KeyChord} с клавишей {@code ALT}) в {@link FocusScope#MAIN},
 * {@link FocusScope#TABLE} и {@link FocusScope#CARD} - {@code ui.menuBar}.</p>
 *
 * <p><b>Области «Где работает» §7:</b> «везде» - все области, включая поля немодальных окон ({@link FocusScope#TEXT_INPUT})
 * и быструю правку ({@link FocusScope#POPUP}); «главное окно» - {@link FocusScope#MAIN}, {@link FocusScope#TABLE},
 * {@link FocusScope#FILTER}, {@link FocusScope#CARD}; «главное окно (не в поле ввода)» (Отменить / Повторить) - без
 * {@link FocusScope#FILTER}, чтобы Ctrl+Z в поле фильтра отменял ввод текста; «таблица» - {@link FocusScope#TABLE}.</p>
 *
 * <p><b>Однозначность.</b> Внутри одной области у сочетания ровно одна привязка (проверяет {@code HotkeyTableTest}).
 * Поэтому Enter в {@link FocusScope#TABLE} - всегда {@code edit.edit}, а переключение группы прошедших по Enter
 * делает контроллер: на строке PAST_HEADER он выполняет {@code past.toggle} (§3.2 сноска, §5.2).</p>
 *
 * <p><b>Не входят в таблицу:</b> Enter и Esc внутри окон форм и всплывающего окна быстрой правки (строки §7
 * «Быстрая правка: сохранить / закрыть» и «Диалог: основная кнопка / отмена») - их клиент передаёт в
 * {@code FormSession}; стрелки, PgUp/PgDn, Home/End таблицы - их обрабатывает сам инструмент ({@code key} возвращает
 * {@code false}); Alt+F4 - закрытие окна ОС ({@code UiIntents.closeMainRequested}); у пункта «Выход» десктопа оно
 * только показывается ({@link #shownAccelerator}).</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class HotkeyTable {

    /** Ширина колонки сочетания в тексте окна «Горячие клавиши» (§7). */
    static final int CHORD_COLUMN = 16;
    /** Ширина колонки действия перед «(в браузере …)» в тексте окна «Горячие клавиши» (§7). */
    static final int ACTION_COLUMN = 34;

    /** «Везде»: главное окно и немодальные окна. */
    private static final Set<FocusScope> EVERYWHERE = Set.copyOf(EnumSet.allOf(FocusScope.class));
    /** «Главное окно». */
    private static final Set<FocusScope> MAIN_WINDOW =
            Set.of(FocusScope.MAIN, FocusScope.TABLE, FocusScope.FILTER, FocusScope.CARD);
    /** «Главное окно (не в поле ввода)». */
    private static final Set<FocusScope> MAIN_NOT_INPUT = Set.of(FocusScope.MAIN, FocusScope.TABLE, FocusScope.CARD);
    /** Строка меню: главное окно без поля фильтра (одиночный Alt нужен полю ввода). */
    private static final Set<FocusScope> MENU_BAR_SCOPES = MAIN_NOT_INPUT;
    /** «Таблица». */
    private static final Set<FocusScope> TABLE = Set.of(FocusScope.TABLE);
    /** Поле фильтра. */
    private static final Set<FocusScope> FILTER = Set.of(FocusScope.FILTER);
    /** Контекстное меню с клавиатуры: таблица и карточка. */
    private static final Set<FocusScope> TABLE_AND_CARD = Set.of(FocusScope.TABLE, FocusScope.CARD);

    /** Alt+F4 у пункта «Выход» на десктопе: только показывается, закрывает окно сама ОС. */
    private static final KeyChord ALT_F4 = KeyChord.parse("Alt+F4");
    /** F10 - строка меню (первое сочетание строки «Строка меню» текста §7). */
    private static final KeyChord F10 = KeyChord.parse("F10");
    /** Shift+F10 - контекстное меню (первое сочетание строки текста §7). */
    private static final KeyChord SHIFT_F10 = KeyChord.parse("Shift+F10");

    /**
     * Строка таблицы §7.
     *
     * @param command    команда
     * @param desktop    сочетание колонки Desktop, показываемое в меню FX и Swing, или {@code null}
     * @param web        сочетание колонки Web, показываемое в меню браузера, или {@code null}
     * @param scopes     области фокуса
     * @param alternates дополнительные сочетания, которые работают, но нигде не показываются
     */
    private record Row(CommandId command, KeyChord desktop, KeyChord web, Set<FocusScope> scopes,
                       List<KeyChord> alternates) {
    }

    /** Таблица в порядке §7, затем сочетания, которых нет в строках §7 (Пробел в таблице, Enter в фильтре). */
    private static final List<Row> ROWS = List.of(
            shown(CommandId.FILE_NEW, "Ctrl+N", "Alt+Shift+N", EVERYWHERE),
            shown(CommandId.FILE_OPEN, "Ctrl+O", "Alt+Shift+O", EVERYWHERE),
            shown(CommandId.FILE_SAVE, "Ctrl+S", "Ctrl+S", EVERYWHERE),
            shown(CommandId.FILE_SAVE_AS, "Ctrl+Shift+S", "Ctrl+Shift+S", EVERYWHERE),
            shown(CommandId.FILE_RENAME, "F2", "F2", MAIN_WINDOW),
            shown(CommandId.FILE_EXPORT_CSV, "Ctrl+Shift+C", "Alt+Shift+C", MAIN_WINDOW),
            shown(CommandId.EDIT_ADD_INCOME, "Ctrl+I", "Ctrl+I", MAIN_WINDOW),
            shown(CommandId.EDIT_ADD_EXPENSE, "Ctrl+E", "Ctrl+E", MAIN_WINDOW),
            shown(CommandId.EDIT_ADD_ONE_TIME, "Ctrl+T", "Alt+Shift+T", MAIN_WINDOW),
            shown(CommandId.EDIT_ADJUST, "Ctrl+J", "Ctrl+J", MAIN_WINDOW),
            shown(CommandId.EDIT_EDIT, "Enter", "Enter", TABLE),
            shown(CommandId.EDIT_DELETE, "Delete", "Delete", TABLE),
            shown(CommandId.EDIT_UNDO, "Ctrl+Z", "Ctrl+Z", MAIN_NOT_INPUT),
            shown(CommandId.EDIT_REDO, "Ctrl+Y", "Ctrl+Y", MAIN_NOT_INPUT, "Ctrl+Shift+Z"),
            shown(CommandId.VIEW_TABLE, "Ctrl+1", "Ctrl+1", MAIN_WINDOW, "Alt+Shift+1"),
            shown(CommandId.VIEW_CHART, "Ctrl+2", "Ctrl+2", MAIN_WINDOW, "Alt+Shift+2"),
            shown(CommandId.VIEW_FOCUS_FILTER, "Ctrl+F", "Ctrl+F", EVERYWHERE),
            hidden(CommandId.FILTER_CLEAR, FILTER, "Esc"),
            shown(CommandId.TOOLS_GOAL, "Ctrl+G", "Ctrl+G", MAIN_WINDOW),
            shown(CommandId.HELP_ABOUT, "F1", "F1", EVERYWHERE),
            hidden(CommandId.UI_MENU_BAR, MENU_BAR_SCOPES, "F10", "Alt"),
            hidden(CommandId.UI_CONTEXT_MENU, TABLE_AND_CARD, "Shift+F10", "Menu"),
            hidden(CommandId.PAST_TOGGLE, TABLE, "Space"),
            hidden(CommandId.FILTER_FOCUS_TABLE, FILTER, "Enter"));

    /** Привязки FX и Swing: показываются сочетания колонки Desktop. */
    private static final List<HotkeyBinding> DESKTOP_BINDINGS = build(true);
    /** Привязки web: показываются сочетания колонки Web. */
    private static final List<HotkeyBinding> WEB_BINDINGS = build(false);

    private HotkeyTable() {
    }

    /**
     * Все привязки для клиента: сочетания Desktop и Web, у каждой отмечено, показывается ли она в меню.
     *
     * @param client вид клиента
     * @return неизменяемый список в порядке таблицы §7
     */
    public static List<HotkeyBinding> bindings(ClientKind client) {
        Objects.requireNonNull(client, "client");
        return client.isDesktop() ? DESKTOP_BINDINGS : WEB_BINDINGS;
    }

    /**
     * Ускоритель, который показывается у пункта меню команды на данной платформе.
     *
     * @param command команда
     * @param client  вид клиента
     * @return сочетание или пусто, если у команды в меню нет ускорителя
     */
    public static Optional<KeyChord> shownAccelerator(CommandId command, ClientKind client) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(client, "client");
        if (command == CommandId.FILE_EXIT) {
            // «(Alt+F4)» колонки Desktop §3.1: окно закрывает ОС тем же путём, что и «Выход»; в браузере сочетания нет.
            return client.isDesktop() ? Optional.of(ALT_F4) : Optional.empty();
        }
        for (Row row : ROWS) {
            if (row.command() == command) {
                return Optional.ofNullable(client.isDesktop() ? row.desktop() : row.web());
            }
        }
        return Optional.empty();
    }

    /**
     * Ищет привязку нажатого сочетания в области фокуса.
     *
     * @param chord  нажатое сочетание
     * @param scope  область фокуса
     * @param client вид клиента
     * @return привязка или пусто, если сочетание в этой области не действует
     */
    public static Optional<HotkeyBinding> find(KeyChord chord, FocusScope scope, ClientKind client) {
        Objects.requireNonNull(chord, "chord");
        Objects.requireNonNull(scope, "scope");
        for (HotkeyBinding binding : bindings(client)) {
            if (binding.chord().equals(chord) && binding.scopes().contains(scope)) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }

    /**
     * Текст для окна «Горячие клавиши» (§6.19): байт в байт блок кода из §7, одинаковый во всех клиентах.
     *
     * <p>Сочетания берутся из этой таблицы (колонка Desktop, в скобках - отличающееся сочетание Web), подписи - из
     * {@code hotkeys_ru.properties}; колонки выравниваются пробелами до {@value #CHORD_COLUMN} и {@value #ACTION_COLUMN}
     * символов.</p>
     *
     * @return текст с переводами строк LF, без завершающего перевода строки
     */
    public static String text() {
        List<String> lines = new ArrayList<>();
        lines.add(line(CommandId.FILE_NEW, "hotkeys.action.new"));
        lines.add(line(CommandId.FILE_OPEN, "hotkeys.action.open"));
        lines.add(line(CommandId.FILE_SAVE, "hotkeys.action.save"));
        lines.add(line(CommandId.FILE_SAVE_AS, "hotkeys.action.saveAs"));
        lines.add(line(CommandId.FILE_RENAME, "hotkeys.action.rename"));
        lines.add(line(CommandId.FILE_EXPORT_CSV, "hotkeys.action.exportCsv"));
        lines.add(line(CommandId.EDIT_ADD_INCOME, "hotkeys.action.addIncome"));
        lines.add(line(CommandId.EDIT_ADD_EXPENSE, "hotkeys.action.addExpense"));
        lines.add(line(CommandId.EDIT_ADD_ONE_TIME, "hotkeys.action.addOneTime"));
        lines.add(line(CommandId.EDIT_ADJUST, "hotkeys.action.adjust"));
        lines.add(line(CommandId.EDIT_EDIT, "hotkeys.action.edit"));
        lines.add(line(CommandId.EDIT_DELETE, "hotkeys.action.delete"));
        lines.add(pairLine(CommandId.EDIT_UNDO, CommandId.EDIT_REDO, "hotkeys.action.undoRedo"));
        lines.add(pairLine(CommandId.VIEW_TABLE, CommandId.VIEW_CHART, "hotkeys.action.tableChart"));
        lines.add(line(CommandId.VIEW_FOCUS_FILTER, "hotkeys.action.focusFilter"));
        lines.add(line(CommandId.TOOLS_GOAL, "hotkeys.action.goal"));
        lines.add(line(CommandId.HELP_ABOUT, "hotkeys.action.about"));
        lines.add(columns(F10.display(), UiText.get("hotkeys.action.menuBar"), null));
        lines.add(columns(SHIFT_F10.display(), UiText.get("hotkeys.action.contextMenu"), null));
        lines.add("");
        lines.add(UiText.get("hotkeys.footer.quickEdit"));
        lines.add(UiText.get("hotkeys.footer.altShift"));
        return String.join("\n", lines);
    }

    private static Row shown(CommandId command, String desktop, String web, Set<FocusScope> scopes,
                             String... alternates) {
        return new Row(command, KeyChord.parse(desktop), KeyChord.parse(web), scopes, parseAll(alternates));
    }

    private static Row hidden(CommandId command, Set<FocusScope> scopes, String... chords) {
        return new Row(command, null, null, scopes, parseAll(chords));
    }

    private static List<KeyChord> parseAll(String... chords) {
        List<KeyChord> result = new ArrayList<>();
        for (String chord : chords) {
            result.add(KeyChord.parse(chord));
        }
        return List.copyOf(result);
    }

    private static List<HotkeyBinding> build(boolean desktop) {
        List<HotkeyBinding> result = new ArrayList<>();
        for (Row row : ROWS) {
            KeyChord shownChord = desktop ? row.desktop() : row.web();
            if (row.desktop() != null) {
                result.add(new HotkeyBinding(row.command(), row.desktop(), row.scopes(),
                        row.desktop().equals(shownChord)));
            }
            // Одинаковое сочетание Desktop и Web - одна привязка, а не две одинаковые.
            if (row.web() != null && !row.web().equals(row.desktop())) {
                result.add(new HotkeyBinding(row.command(), row.web(), row.scopes(), row.web().equals(shownChord)));
            }
            for (KeyChord alternate : row.alternates()) {
                result.add(new HotkeyBinding(row.command(), alternate, row.scopes(), false));
            }
        }
        return List.copyOf(result);
    }

    private static String line(CommandId command, String actionKey) {
        KeyChord desktop = shownAccelerator(command, ClientKind.FX).orElseThrow();
        KeyChord web = shownAccelerator(command, ClientKind.WEB).orElseThrow();
        return columns(desktop.display(), UiText.get(actionKey), web.equals(desktop) ? null : web.display());
    }

    private static String pairLine(CommandId first, CommandId second, String actionKey) {
        KeyChord firstChord = shownAccelerator(first, ClientKind.FX).orElseThrow();
        KeyChord secondChord = shownAccelerator(second, ClientKind.FX).orElseThrow();
        // Строки-пары §7 одинаковы на десктопе и в браузере, поэтому «(в браузере …)» у них не бывает.
        return columns(UiText.get("hotkeys.pair", firstChord.display(), secondChord.display()), UiText.get(actionKey),
                null);
    }

    private static String columns(String chord, String action, String webChordOrNull) {
        StringBuilder sb = new StringBuilder(pad(chord, CHORD_COLUMN));
        if (webChordOrNull == null) {
            sb.append(action);
        } else {
            sb.append(pad(action, ACTION_COLUMN)).append(UiText.get("hotkeys.web", webChordOrNull));
        }
        return sb.toString();
    }

    private static String pad(String text, int width) {
        // Слишком длинный текст всё равно отделяется от следующей колонки хотя бы одним пробелом.
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }
}
