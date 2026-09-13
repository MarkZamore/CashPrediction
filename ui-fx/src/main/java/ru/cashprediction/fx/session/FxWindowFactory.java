package ru.cashprediction.fx.session;

import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowFactory;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.fx.action.FxActions;
import ru.cashprediction.fx.action.FxAppContext;
import ru.cashprediction.fx.action.QuickEditOpener;
import ru.cashprediction.fx.dialog.OpenRequest;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Фабрика окон JavaFX-клиента: по типу окна из словаря {@link WindowType} и контексту вызывает тот же метод
 * фасада {@link FxActions}, что и пункт меню.
 *
 * <p>Используется в двух местах: координатором восстановления сессии ({@link #open(WindowState, String, Consumer, Consumer)},
 * окно получает значения полей из снимка) и самотестом (команда {@code open}, окно открывается как обычная команда
 * пользователя). Благодаря одному пути открытия восстановленное окно ведёт себя так же, как открытое вручную.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class FxWindowFactory implements WindowFactory {

    private final FxActions actions;
    private final QuickEditOpener quickEdit;
    private final Supplier<LocalDate> today;
    private final Consumer<StatefulWindow> registerManual;

    /**
     * Создаёт фабрику.
     *
     * @param actions        фасад команд
     * @param quickEdit      открыватель быстрой правки суммы (живёт у таблицы главного окна)
     * @param today          сегодняшняя дата
     * @param registerManual регистрация в рекордере для окон, открытых не при восстановлении (быстрая правка)
     */
    public FxWindowFactory(FxActions actions, QuickEditOpener quickEdit, Supplier<LocalDate> today,
                           Consumer<StatefulWindow> registerManual) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.quickEdit = Objects.requireNonNull(quickEdit, "quickEdit");
        this.today = Objects.requireNonNull(today, "today");
        this.registerManual = Objects.requireNonNull(registerManual, "registerManual");
    }

    /**
     * Создаёт фабрику по контексту приложения: быстрая правка — {@link FxAppContext#quickEdit()},
     * сегодня — {@link FxAppContext#today()}, регистрация вручную открытой быстрой правки — в рекордере контекста.
     *
     * @param actions фасад команд
     * @param context контекст приложения
     */
    public FxWindowFactory(FxActions actions, FxAppContext context) {
        this(actions, quickEditOf(context), context::today, window -> context.recorder().register(window));
    }

    private static QuickEditOpener quickEditOf(FxAppContext context) {
        Objects.requireNonNull(context, "context");
        // Открыватель берётся лениво: главное окно (его реализация) может появиться позже фабрики.
        return (state, onShown, onFailed) -> context.quickEdit().open(state, onShown, onFailed);
    }

    /**
     * Открывает окно из снимка: вызывает метод фасада, соответствующий типу окна; состояние (поля и геометрия)
     * применяется до показа, а о показе или отказе сообщается колбэками координатору.
     *
     * @param state    состояние окна из снимка (идентификатор уже назначен координатором)
     * @param ownerId  владелец в текущем сеансе
     * @param onShown  вызвать, когда окно показано
     * @param onFailed вызвать с причиной, если окно открыть нельзя
     */
    @Override
    public void open(WindowState state, String ownerId, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        OpenRequest request = OpenRequest.restore(state, ownerId, onShown, onFailed);
        try {
            dispatch(state.type(), state.context(), state.fields(), request, state);
        } catch (RuntimeException e) {
            request.fail(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    /**
     * Открывает окно как команду пользователя (самотест, команда {@code open}).
     *
     * @param type    тип окна
     * @param context контекст окна ({@code mode}, {@code ruleId}, {@code purpose}, ...)
     * @param ownerId владелец ({@code main} или идентификатор окна)
     * @throws IllegalArgumentException если контекст неполный или назначение неизвестно
     */
    public void openCommand(WindowType type, Map<String, String> context, String ownerId) {
        OpenRequest request = OpenRequest.ownedBy(ownerId);
        Map<String, String> empty = Map.of();
        WindowState pseudo = new WindowState("w0", type, type.defaultModal(), ownerId, null, new LinkedHashMap<>(context), empty);
        dispatch(type, context, empty, request, pseudo);
    }

    private void dispatch(WindowType type, Map<String, String> context, Map<String, String> fields, OpenRequest request,
                          WindowState state) {
        if (type == null) {
            fail(request, "неизвестный тип окна");
            return;
        }
        String mode = context.getOrDefault(WindowType.CONTEXT_MODE, "");
        String purpose = context.getOrDefault(WindowType.CONTEXT_PURPOSE, "");
        switch (type) {
            case NEW_PLAN_WIZARD -> actions.newPlan(request);
            case PLAN_SETTINGS -> actions.planSettings(request);
            case RULE_EDITOR -> {
                String ruleId = context.getOrDefault(WindowType.CONTEXT_RULE_ID, "");
                if (WindowType.MODE_EDIT.equals(mode) && !ruleId.isBlank()) {
                    actions.editRule(new RuleId(ruleId), request);
                } else {
                    actions.addRule(kind(fields, context), request);
                }
            }
            case ONE_TIME_EDITOR -> {
                String txId = context.getOrDefault(WindowType.CONTEXT_TX_ID, "");
                if (WindowType.MODE_EDIT.equals(mode) && !txId.isBlank()) {
                    actions.editOneTime(new TxId(txId), request);
                } else {
                    LocalDate date = FieldValues.parseDate(context.getOrDefault("date", "")).orElse(null);
                    actions.addOneTime(date, kind(fields, context), request);
                }
            }
            case ADJUSTMENT_EDITOR -> {
                OccurrenceKey key = occurrenceKey(context);
                if (key == null) {
                    fail(request, "в контексте нет правила (ruleId) и исходной даты (originalDate)");
                } else {
                    actions.adjustOccurrence(key, request);
                }
            }
            case GOAL_CALCULATOR -> actions.goalCalculator(request);
            case TEXT_INPUT -> {
                switch (purpose) {
                    case "rename" -> actions.rename(request);
                    case "reconcile" -> actions.reconcile(request);
                    case "customMonths" -> actions.customMonths(request);
                    case "customCurrency" -> actions.customCurrency(request);
                    default -> fail(request, "неизвестное назначение окна ввода: «" + purpose + "»");
                }
            }
            case CHOICE -> {
                switch (purpose) {
                    case "currency" -> actions.currency(request);
                    case "openPlan" -> actions.openPlan(request);
                    default -> fail(request, "неизвестное назначение окна выбора: «" + purpose + "»");
                }
            }
            case ALERT -> openAlert(purpose, mode, context.getOrDefault(WindowType.CONTEXT_TARGET_ID, ""), request);
            case CSV_EXPORT -> actions.exportCsv(request);
            case QUICK_EDIT_POPUP -> {
                if (occurrenceKey(context) == null) {
                    fail(request, "в контексте нет правила (ruleId) и исходной даты (originalDate)");
                } else if (request.isRestore()) {
                    quickEdit.open(state, request.onShown(), request.onFailed());
                } else {
                    quickEdit.open(state, registerManual, reason -> {
                        throw new IllegalArgumentException(reason);
                    });
                }
            }
        }
    }

    private void openAlert(String purpose, String mode, String targetId, OpenRequest request) {
        // Координатор переводит окно в mode=create, если объекта уже нет в плане: подтверждать удаление нечего.
        boolean targetMissing = WindowType.MODE_CREATE.equals(mode);
        switch (purpose) {
            case "deleteRule" -> {
                if (targetMissing || targetId.isBlank()) {
                    fail(request, "удаляемой операции уже нет в плане");
                } else {
                    actions.deleteRule(new RuleId(targetId), request);
                }
            }
            case "deleteOneTime" -> {
                if (targetMissing || targetId.isBlank()) {
                    fail(request, "удаляемой операции уже нет в плане");
                } else {
                    actions.deleteOneTime(new TxId(targetId), request);
                }
            }
            case "actualize" -> actions.actualize(request);
            case "applyWhatIf" -> actions.applyWhatIf(request);
            case "clearSnapshots" -> actions.clearSnapshots(request);
            default -> fail(request, "неизвестное назначение подтверждения: «" + purpose + "»");
        }
    }

    private static Kind kind(Map<String, String> fields, Map<String, String> context) {
        String value = fields.getOrDefault("kind", context.getOrDefault("kind", ""));
        return Kind.EXPENSE.name().equals(value) ? Kind.EXPENSE : Kind.INCOME;
    }

    private static OccurrenceKey occurrenceKey(Map<String, String> context) {
        String ruleId = context.getOrDefault(WindowType.CONTEXT_RULE_ID, "");
        String date = context.getOrDefault(WindowType.CONTEXT_ORIGINAL_DATE, "");
        if (ruleId.isBlank() || date.isBlank()) {
            return null;
        }
        try {
            return new OccurrenceKey(new RuleId(ruleId), LocalDate.parse(date));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void fail(OpenRequest request, String reason) {
        if (request.isRestore()) {
            request.fail(reason);
        } else {
            throw new IllegalArgumentException(reason);
        }
    }

    /**
     * Сегодняшняя дата фабрики (для открытия разовой операции без даты).
     *
     * @return сегодня
     */
    public LocalDate today() {
        return today.get();
    }
}
