package ru.cashprediction.core.ui.menu;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientKind;

/**
 * Построение моделей меню, тулбара и контекстных меню из состояния (архитектура §3.3, спецификация v2, §3-§5).
 *
 * <p>Все методы - чистые функции состояния: одно и то же {@link AppState} даёт равные модели. Доступность берётся
 * из {@code CommandAvailability}, ускорители - из {@code HotkeyTable.shownAccelerator} для {@code client}, тексты и
 * подсказки - из {@code UiText} ({@code menu.<id>}, {@code menu.<id>.tip}, {@code toolbar.<id>}, {@code ctx.<target>.<id>}).
 * Соседние, начальные и конечные разделители удаляются; у каждого узла стабильный id.</p>
 *
 * <p><b>Id узлов</b> - ключи дампа и сценариев самотеста: меню {@code file}, {@code edit}, {@code view},
 * {@code tools}, {@code recovery}, {@code help}; пункты - id команды ({@code file.new}, {@code view.period.M3},
 * {@code whatIf.extra}); подменю {@code file.recent}, {@code tools.whatIf}, {@code recovery.simulate}; пункты
 * «Недавние» {@code file.recent.N} (пустой список - {@code file.recent.empty}); разделители {@code <меню>.sep.N};
 * тулбар - {@code tb.*} ({@link ToolbarBuilder}); контекстные меню - {@code ctx.<цель>.*} ({@link ContextMenuBuilder}).</p>
 *
 * <p>Отличия web (§10): в меню «Восстановление» одно отключённое радио {@code recovery.store.server} вместо двух;
 * подсказка «Выход» - «Закрыть программу и остановить сервер»; ускорители - колонка «Web».</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class MenuModels {

    /** Группа радио «Таблица / График» и переключателей тулбара. */
    public static final String GROUP_MODE = "mode";
    /** Группа радио периода (строка меню и кнопка-меню {@code tb.period}). */
    public static final String GROUP_PERIOD = "period";
    /** Группа радио хранилища по умолчанию. */
    public static final String GROUP_STORE = "store";

    /** Минимум шкалы слайдера горизонта (§3.3). */
    public static final int HORIZON_SLIDER_MIN = 1;
    /** Максимум шкалы слайдера горизонта (§3.3): горизонт длиннее показывается ползунком на максимуме. */
    public static final int HORIZON_SLIDER_MAX = 120;
    /** Шаг основных делений слайдера горизонта (§3.3). */
    public static final int HORIZON_SLIDER_MAJOR_TICK = 12;

    /** Минимум спиннера доп. экономии «что-если» (§3.4). */
    public static final long WHAT_IF_EXTRA_MIN = 0;
    /** Максимум спиннера доп. экономии «что-если» (§3.4). */
    public static final long WHAT_IF_EXTRA_MAX = 10_000_000;
    /** Шаг спиннера доп. экономии «что-если» (§3.4). */
    public static final long WHAT_IF_EXTRA_STEP = 1_000;
    /** Коэффициент доходов, при котором отмечен флажок «Доходы −10 %» (§3.4). */
    public static final BigDecimal WHAT_IF_INCOME_FACTOR = new BigDecimal("0.90");
    /** Коэффициент расходов, при котором отмечен флажок «Расходы +10 %» (§3.4). */
    public static final BigDecimal WHAT_IF_EXPENSE_FACTOR = new BigDecimal("1.10");

    private MenuModels() {
    }

    /**
     * Строка меню главного окна.
     *
     * @param state  состояние
     * @param client вид клиента
     * @return модель шести меню
     */
    public static MenuBarModel menuBar(AppState state, ClientKind client) {
        return MenuBarBuilder.build(new MenuItems(state, client));
    }

    /**
     * Тулбар главного окна.
     *
     * @param state  состояние
     * @param client вид клиента
     * @return модель тулбара
     */
    public static ToolbarModel toolbar(AppState state, ClientKind client) {
        return ToolbarBuilder.build(new MenuItems(state, client));
    }

    /**
     * Контекстное меню объекта (строка, итог, группа прошедших, карточка, график, предпросмотр).
     *
     * @param state  состояние
     * @param target объект
     * @param client вид клиента
     * @return пункты меню; пустой список - меню не показывается (например, щелчок в пустом месте таблицы)
     */
    public static List<MenuNode> contextMenu(AppState state, ContextTarget target, ClientKind client) {
        return contextMenu(state, target, client, ContextFacts.MODELS);
    }

    /**
     * Контекстное меню с заданным источником дат карточки и графика (для тестов пунктов меню).
     *
     * @param state  состояние
     * @param target объект
     * @param client вид клиента
     * @param facts  источник дат
     * @return пункты меню
     */
    static List<MenuNode> contextMenu(AppState state, ContextTarget target, ClientKind client, ContextFacts facts) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(facts, "facts");
        return ContextMenuBuilder.build(new MenuItems(state, client), target, facts);
    }
}
