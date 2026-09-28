package ru.cashprediction.core.ui.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandAvailability;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.HotkeyTable;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.text.Plurals;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.DesignTokens;

/**
 * Общие фабрики узлов меню для строки меню, тулбара и контекстных меню одного состояния
 * ({@link MenuModels}): доступность - из {@link CommandAvailability}, ускорители - из
 * {@link HotkeyTable#shownAccelerator} для клиента, тексты - из {@link UiText}.
 *
 * <p>Одни и те же пункты «Период», слайдер горизонта и пункты «Что-если» стоят и в строке меню, и в кнопках-меню
 * тулбара (§4 п. 5-6): они строятся здесь один раз, поэтому не расходятся.</p>
 *
 * <p>Экземпляр неизменяем и потокобезопасен.</p>
 */
final class MenuItems {

    private final AppState state;
    private final ClientKind client;

    /**
     * Создаёт фабрику для состояния.
     *
     * @param state  состояние приложения
     * @param client вид клиента
     */
    MenuItems(AppState state, ClientKind client) {
        this.state = Objects.requireNonNull(state, "state");
        this.client = Objects.requireNonNull(client, "client");
    }

    /** @return состояние приложения */
    AppState state() {
        return state;
    }

    /** @return вид клиента */
    ClientKind client() {
        return client;
    }

    /**
     * Доступна ли команда.
     *
     * @param command команда
     * @param args    аргументы
     * @return {@code true}, если доступна
     */
    boolean enabled(CommandId command, CommandArgs args) {
        return CommandAvailability.of(command, args, state).enabled();
    }

    /**
     * Показываемый ускоритель команды на платформе клиента.
     *
     * @param command команда
     * @return сочетание или {@code null}
     */
    KeyChord accel(CommandId command) {
        return HotkeyTable.shownAccelerator(command, client).orElse(null);
    }

    /**
     * Пункт строки меню без аргументов: метка, подсказка и ускоритель команды.
     *
     * @param id      id узла
     * @param command команда
     * @param textKey ключ метки
     * @param tipKey  ключ подсказки
     * @return пункт
     */
    MenuNode.Action action(String id, CommandId command, String textKey, String tipKey) {
        return action(id, command, CommandArgs.NONE, UiText.get(textKey), accel(command), UiText.get(tipKey));
    }

    /**
     * Пункт с готовыми текстами.
     *
     * @param id      id узла
     * @param command команда
     * @param args    аргументы
     * @param text    метка
     * @param accel   показываемый ускоритель или {@code null}
     * @param tooltip подсказка
     * @return пункт
     */
    MenuNode.Action action(String id, CommandId command, CommandArgs args, String text, KeyChord accel,
                           String tooltip) {
        // JavaFX: MenuItem → Swing: JMenuItem → Web: div[role=menuitem]
        return new MenuNode.Action(id, command, args, text, accel, tooltip, enabled(command, args));
    }

    /**
     * Флажок строки меню.
     *
     * @param id      id узла
     * @param command команда-переключатель
     * @param textKey ключ метки
     * @param tipKey  ключ подсказки
     * @param checked отмечен ли
     * @return флажок
     */
    MenuNode.Check check(String id, CommandId command, String textKey, String tipKey, boolean checked) {
        return checkWithText(id, command, UiText.get(textKey), UiText.get(tipKey), checked);
    }

    /**
     * Флажок с готовыми текстами (без аргументов).
     *
     * @param id      id узла
     * @param command команда-переключатель
     * @param text    метка
     * @param tooltip подсказка
     * @param checked отмечен ли
     * @return флажок
     */
    MenuNode.Check checkWithText(String id, CommandId command, String text, String tooltip, boolean checked) {
        // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: div[role=menuitemcheckbox]
        return new MenuNode.Check(id, command, CommandArgs.NONE, text, accel(command), tooltip,
                enabled(command, CommandArgs.NONE), checked);
    }

    /**
     * Радио-пункт группы.
     *
     * @param id       id узла
     * @param group    группа
     * @param command  команда выбора
     * @param textKey  ключ метки
     * @param tipKey   ключ подсказки
     * @param selected выбран ли
     * @return радио-пункт
     */
    MenuNode.Radio radio(String id, String group, CommandId command, String textKey, String tipKey, boolean selected) {
        // JavaFX: RadioMenuItem + ToggleGroup → Swing: JRadioButtonMenuItem + ButtonGroup → Web: div[role=menuitemradio]
        return new MenuNode.Radio(id, group, command, CommandArgs.NONE, UiText.get(textKey), accel(command),
                UiText.get(tipKey), enabled(command, CommandArgs.NONE), selected);
    }

    /**
     * Подменю.
     *
     * @param id       id узла
     * @param textKey  ключ метки
     * @param tipKey   ключ подсказки или {@code null} - без подсказки
     * @param children дочерние узлы (разделители чистятся)
     * @return подменю
     */
    static MenuNode.Submenu submenu(String id, String textKey, String tipKey, List<MenuNode> children) {
        // JavaFX: Menu → Swing: JMenu → Web: вложенный div[role=menu]
        return new MenuNode.Submenu(id, UiText.get(textKey), tipKey == null ? "" : UiText.get(tipKey), true,
                clean(children));
    }

    /**
     * Разделитель.
     *
     * @param id id узла
     * @return разделитель
     */
    static MenuNode.Separator separator(String id) {
        // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.Separator → Web: hr[role=separator]
        return new MenuNode.Separator(id);
    }

    /**
     * Пять радио периода (§3.3 п. 11-15), разделитель, слайдер горизонта и «Горизонт: другое число месяцев…» -
     * общая часть меню «Вид» и кнопки-меню {@code tb.period} (§4 п. 5).
     *
     * @param separatorId id разделителя перед слайдером
     * @return узлы по порядку
     */
    List<MenuNode> periodItems(String separatorId) {
        PeriodChoice period = state.view().period();
        List<MenuNode> items = new ArrayList<>();
        items.add(radio("view.period.M3", MenuModels.GROUP_PERIOD, CommandId.VIEW_PERIOD_M3, "menu.view.period.M3",
                "menu.view.period.M3.tip", period == PeriodChoice.M3));
        items.add(radio("view.period.M6", MenuModels.GROUP_PERIOD, CommandId.VIEW_PERIOD_M6, "menu.view.period.M6",
                "menu.view.period.M6.tip", period == PeriodChoice.M6));
        items.add(radio("view.period.M12", MenuModels.GROUP_PERIOD, CommandId.VIEW_PERIOD_M12, "menu.view.period.M12",
                "menu.view.period.M12.tip", period == PeriodChoice.M12));
        items.add(radio("view.period.M24", MenuModels.GROUP_PERIOD, CommandId.VIEW_PERIOD_M24, "menu.view.period.M24",
                "menu.view.period.M24.tip", period == PeriodChoice.M24));
        items.add(radio("view.period.ALL", MenuModels.GROUP_PERIOD, CommandId.VIEW_PERIOD_ALL, "menu.view.period.ALL",
                "menu.view.period.ALL.tip", period == PeriodChoice.ALL));
        items.add(separator(separatorId));
        items.add(horizonSlider());
        items.add(action("view.horizonMonths", CommandId.VIEW_HORIZON_MONTHS, CommandArgs.NONE,
                UiText.get("menu.view.horizonMonths"), accel(CommandId.VIEW_HORIZON_MONTHS),
                UiText.get("menu.view.horizonMonths.tip", Horizon.MAX_MONTHS)));
        return items;
    }

    /**
     * Текст периода: метка радио и текст кнопки {@code tb.period} («Период: 12 месяцев»).
     *
     * @param period период
     * @return текст
     */
    static String periodText(PeriodChoice period) {
        return switch (period) {
            case M3 -> UiText.get("menu.view.period.M3");
            case M6 -> UiText.get("menu.view.period.M6");
            case M12 -> UiText.get("menu.view.period.M12");
            case M24 -> UiText.get("menu.view.period.M24");
            case ALL -> UiText.get("menu.view.period.ALL");
        };
    }

    /**
     * Слайдер горизонта (§3.3 п. 16): шкала 1..120, деления через 12, ширина 240; подпись для каждого положения
     * «Горизонт плана: N месяцев». Горизонт больше 120 месяцев: ползунок на 120, подпись - настоящий горизонт.
     *
     * @return узел слайдера
     */
    MenuNode.Slider horizonSlider() {
        Plan plan = state.document().plan();
        long months = horizonMonths(plan);
        List<String> labels = IntStream.rangeClosed(MenuModels.HORIZON_SLIDER_MIN, MenuModels.HORIZON_SLIDER_MAX)
                .mapToObj(n -> UiText.get("menu.view.horizonSlider", Plurals.count(Plurals.MONTH, n)))
                .toList();
        int value = (int) Math.min(months, MenuModels.HORIZON_SLIDER_MAX);
        String currentLabel = months > MenuModels.HORIZON_SLIDER_MAX
                ? UiText.get("menu.view.horizonSlider", UiFormats.horizonLabel(plan.horizon(), plan.startDate()))
                : null;
        // JavaFX: CustomMenuItem + Slider → Swing: SwingSliderMenuItem → Web: input type=range в пункте меню
        return new MenuNode.Slider("view.horizonSlider", CommandId.VIEW_HORIZON_SLIDER, MenuModels.HORIZON_SLIDER_MIN,
                MenuModels.HORIZON_SLIDER_MAX, MenuModels.HORIZON_SLIDER_MAJOR_TICK, value, labels, currentLabel,
                UiText.get("menu.view.horizonSlider.tip"), DesignTokens.SLIDER_WIDTH);
    }

    /**
     * Пункты «Что-если» (§3.4 п. 2.1-2.5) - общая часть подменю «Инструменты → Что-если» и кнопки-меню
     * {@code tb.whatIf} (§4 п. 6).
     *
     * @param separatorId id разделителя перед «Применить к плану…»
     * @return узлы по порядку
     */
    List<MenuNode> whatIfItems(String separatorId) {
        WhatIf whatIf = state.view().whatIf();
        List<MenuNode> items = new ArrayList<>();
        items.add(check("whatIf.income", CommandId.WHAT_IF_INCOME, "menu.whatIf.income", "menu.whatIf.income.tip",
                whatIf.incomeFactor().compareTo(MenuModels.WHAT_IF_INCOME_FACTOR) == 0));
        items.add(check("whatIf.expense", CommandId.WHAT_IF_EXPENSE, "menu.whatIf.expense", "menu.whatIf.expense.tip",
                whatIf.expenseFactor().compareTo(MenuModels.WHAT_IF_EXPENSE_FACTOR) == 0));
        // Спиннер целых единиц валюты: копейки доп. экономии в меню не показываются.
        long extra = whatIf.extraMonthlySaving().minor() / 100;
        // JavaFX: CustomMenuItem + Spinner → Swing: SwingSpinnerMenuItem → Web: input type=number в пункте меню
        items.add(new MenuNode.Spinner("whatIf.extra", CommandId.WHAT_IF_EXTRA,
                UiText.get("menu.whatIf.extra", state.document().plan().currency()), MenuModels.WHAT_IF_EXTRA_MIN,
                MenuModels.WHAT_IF_EXTRA_MAX, MenuModels.WHAT_IF_EXTRA_STEP, extra, UiText.get("menu.whatIf.extra.tip"),
                DesignTokens.SPINNER_FIELD_WIDTH, DesignTokens.WHAT_IF_SPINNER_DELAY_MS));
        items.add(separator(separatorId));
        items.add(action("whatIf.apply", CommandId.WHAT_IF_APPLY, "menu.whatIf.apply", "menu.whatIf.apply.tip"));
        items.add(action("whatIf.reset", CommandId.WHAT_IF_RESET, "menu.whatIf.reset", "menu.whatIf.reset.tip"));
        return items;
    }

    /**
     * Удаляет начальные, конечные и соседние разделители, в том числе во вложенных подменю.
     *
     * @param nodes узлы
     * @return неизменяемый очищенный список
     */
    static List<MenuNode> clean(List<MenuNode> nodes) {
        List<MenuNode> result = new ArrayList<>();
        for (MenuNode node : nodes) {
            MenuNode cleaned = node instanceof MenuNode.Submenu submenu
                    ? new MenuNode.Submenu(submenu.id(), submenu.text(), submenu.tooltip(), submenu.enabled(),
                    clean(submenu.children()))
                    : node;
            if (cleaned instanceof MenuNode.Separator
                    && (result.isEmpty() || result.getLast() instanceof MenuNode.Separator)) {
                continue;
            }
            result.add(cleaned);
        }
        while (!result.isEmpty() && result.getLast() instanceof MenuNode.Separator) {
            result.removeLast();
        }
        return List.copyOf(result);
    }

    /**
     * Горизонт в месяцах для слайдера (§3.3): MONTHS - число месяцев, YEARS - ×12, UNTIL - целые месяцы, минимум 1.
     *
     * @param plan план
     * @return число месяцев
     */
    static long horizonMonths(Plan plan) {
        return switch (plan.horizon()) {
            case Horizon.Months months -> months.count();
            case Horizon.Years years -> years.count() * 12L;
            case Horizon.Until until -> until.approximateMonths(plan.startDate());
        };
    }
}
