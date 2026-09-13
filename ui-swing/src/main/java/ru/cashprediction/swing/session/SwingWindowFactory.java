package ru.cashprediction.swing.session;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowFactory;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.swing.action.OpenRequest;
import ru.cashprediction.swing.action.Purposes;
import ru.cashprediction.swing.action.SwingActions;

/**
 * Фабрика окон Swing-клиента: по типу окна из словаря {@link WindowType} и контексту вызывает тот же метод фасада
 * {@link SwingActions}, что и пункт меню. Зеркало {@code FxWindowFactory} JavaFX-клиента.
 *
 * <p><b>Восстановление.</b> {@code RestoreCoordinator} вызывает {@link #open} для каждого окна снимка по очереди.
 * Фасад создаёт окно, <i>до показа</i> переносит в него состояние из снимка ({@code applyState}) и отдаёт его хосту
 * диалогов. Хост показывает окно из свежего {@code SwingUtilities.invokeLater}: {@code setVisible(true)} у модального
 * {@code JDialog} (DOCUMENT_MODAL) не возвращается, пока окно открыто, и вызов прямо из текущего события заблокировал
 * бы цепочку. О показе хост сообщает из {@code windowOpened} — уже внутри вложенного цикла событий показанного окна;
 * координатор в этом колбэке открывает следующее окно, и оно корректно ложится поверх предыдущего.</p>
 *
 * <p>Нативные окна выбора файла и папки ({@code JFileChooser}) не восстанавливаются ни в одном клиенте.</p>
 *
 * <p>Только поток EDT.</p>
 */
public final class SwingWindowFactory implements WindowFactory {

    private final SwingActions actions;

    /**
     * Создаёт фабрику.
     *
     * @param actions фасад команд
     */
    public SwingWindowFactory(SwingActions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /** {@inheritDoc} */
    @Override
    public void open(WindowState state, String ownerId, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        OpenRequest request = OpenRequest.restore(state, ownerId, onShown, onFailed);
        try {
            dispatch(state.type(), state.context(), state.fields(), request);
        } catch (RuntimeException e) {
            // Ошибка создания одного окна не должна обрывать восстановление остальных.
            onFailed.accept(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    /**
     * Открывает окно как команду пользователя (для отладки и ручной проверки словаря окон).
     *
     * @param type    тип окна
     * @param context контекст окна ({@code mode}, {@code ruleId}, {@code purpose}, ...)
     * @param ownerId владелец ({@code main} или идентификатор окна)
     * @throws IllegalArgumentException если контекст неполный или назначение неизвестно
     */
    public void openCommand(WindowType type, Map<String, String> context, String ownerId) {
        dispatch(Objects.requireNonNull(type, "type"), new LinkedHashMap<>(context), Map.of(), OpenRequest.ownedBy(ownerId));
    }

    /**
     * Открывает окно по готовому запросу — так же, как это сделала бы команда пользователя. Используется самотестом
     * ({@code OpenRequest.scripted}): о показе и неудаче он узнаёт из колбэков запроса, а не из диалогов.
     *
     * @param type    тип окна
     * @param context контекст окна ({@code mode}, {@code ruleId}, {@code purpose}, ...)
     * @param request запрос открытия (не восстановление)
     * @throws IllegalArgumentException если контекст неполный или назначение неизвестно
     */
    public void openScripted(WindowType type, Map<String, String> context, OpenRequest request) {
        dispatch(Objects.requireNonNull(type, "type"), new LinkedHashMap<>(context), Map.of(), request);
    }

    private void dispatch(WindowType type, Map<String, String> context, Map<String, String> fields, OpenRequest request) {
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
                    // Режим create (в том числе когда координатор не нашёл правило): тип берём из введённых полей.
                    actions.addRule(kind(fields, context), request);
                }
            }
            case ONE_TIME_EDITOR -> {
                String txId = context.getOrDefault(WindowType.CONTEXT_TX_ID, "");
                if (WindowType.MODE_EDIT.equals(mode) && !txId.isBlank()) {
                    actions.editOneTime(new TxId(txId), request);
                } else {
                    actions.addOneTime(null, kind(fields, context), request);
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
                    case Purposes.RENAME -> actions.rename(request);
                    case Purposes.RECONCILE -> actions.reconcile(request);
                    case Purposes.CUSTOM_MONTHS -> actions.customMonths(request);
                    case Purposes.CUSTOM_CURRENCY -> actions.customCurrency(request);
                    default -> fail(request, "неизвестное назначение окна ввода: «" + purpose + "»");
                }
            }
            case CHOICE -> {
                switch (purpose) {
                    case Purposes.CURRENCY -> actions.currency(request);
                    case Purposes.OPEN_PLAN -> actions.openPlan(request);
                    default -> fail(request, "неизвестное назначение окна выбора: «" + purpose + "»");
                }
            }
            case ALERT -> openAlert(purpose, mode, context.getOrDefault(WindowType.CONTEXT_TARGET_ID, ""), request);
            case CSV_EXPORT -> actions.exportCsv(request);
            case QUICK_EDIT_POPUP -> {
                OccurrenceKey key = occurrenceKey(context);
                if (key == null) {
                    fail(request, "в контексте нет правила (ruleId) и исходной даты (originalDate)");
                } else {
                    actions.quickEdit(key, request);
                }
            }
        }
    }

    private void openAlert(String purpose, String mode, String targetId, OpenRequest request) {
        // Координатор переводит окно в mode=create, если объекта уже нет в плане: подтверждать удаление нечего.
        boolean targetMissing = WindowType.MODE_CREATE.equals(mode);
        switch (purpose) {
            case Purposes.DELETE_RULE -> {
                if (targetMissing || targetId.isBlank()) {
                    fail(request, "удаляемой операции уже нет в плане");
                } else {
                    actions.deleteRule(new RuleId(targetId), request);
                }
            }
            case Purposes.DELETE_ONE_TIME -> {
                if (targetMissing || targetId.isBlank()) {
                    fail(request, "удаляемой операции уже нет в плане");
                } else {
                    actions.deleteOneTime(new TxId(targetId), request);
                }
            }
            case Purposes.ACTUALIZE -> actions.actualize(request);
            case Purposes.APPLY_WHAT_IF -> actions.applyWhatIf(request);
            case Purposes.CLEAR_SNAPSHOTS -> actions.clearSnapshots(request);
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
            return new OccurrenceKey(new RuleId(ruleId), LocalDate.parse(date.strip()));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void fail(OpenRequest request, String reason) {
        if (request.isRestore()) {
            request.onFailed().accept(reason);
        } else {
            throw new IllegalArgumentException(reason);
        }
    }
}
