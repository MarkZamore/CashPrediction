package ru.cashprediction.core.ui.forms.simple;

import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.alert.AlertSpec;

/**
 * Восстанавливаемые подтверждения (представление {@code CONFIRM}): deleteRule, deleteOneTime, actualize,
 * applyWhatIf, clearSnapshots. Строит сообщение ({@code AlertCatalog}) и описывает, что сделать при подтверждении,
 * чтобы и меню, и {@code CoreWindowFactory} при восстановлении шли одним путём.
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class ConfirmForms {

    /**
     * Подтверждение и его действие.
     *
     * @param spec            сообщение
     * @param confirmButtonId id кнопки, означающей «да»
     * @param undoText        описание действия для отмены ({@code undo.*}) или пустая строка, если план не меняется
     * @param edit            изменение плана при подтверждении или {@code null} (clearSnapshots меняет не план)
     * @param statusKey       статус после выполнения ({@code status.msg.*})
     */
    public record Confirmation(AlertSpec spec, String confirmButtonId, String undoText, UnaryOperator<Plan> edit,
                               String statusKey) {
        /** Проверяет поля. */
        public Confirmation {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(confirmButtonId, "confirmButtonId");
            undoText = Objects.requireNonNullElse(undoText, "");
            statusKey = Objects.requireNonNullElse(statusKey, "");
        }
    }

    private ConfirmForms() {
    }

    /**
     * §6.11 удаление регулярной операции вместе с её корректировками; отмена {@code undo.ruleDelete}, статус
     * {@code status.msg.ruleDeleted}.
     *
     * @param state  состояние
     * @param ruleId id правила
     * @return подтверждение или пусто, если правила нет (контроллер показывает {@code err.notFound})
     */
    public static Optional<Confirmation> deleteRule(AppState state, String ruleId) {
        throw new UnsupportedOperationException("S1: core-forms-framework — ConfirmForms.deleteRule");
    }

    /**
     * §6.11 удаление разовой операции; отмена {@code undo.oneTimeDelete}, статус {@code status.msg.oneTimeDeleted}.
     *
     * @param state state
     * @param txId  id операции
     * @return подтверждение или пусто, если операции нет
     */
    public static Optional<Confirmation> deleteOneTime(AppState state, String txId) {
        throw new UnsupportedOperationException("S1: core-forms-framework — ConfirmForms.deleteOneTime");
    }

    /**
     * §6.25 актуализация; пусто, если сегодня ≤ начала ({@code info.actualizeNothing}) или прогноз не рассчитан
     * ({@code err.forecast}) — контроллер показывает соответствующее сообщение.
     *
     * @param state состояние
     * @return подтверждение или пусто
     */
    public static Optional<Confirmation> actualize(AppState state) {
        throw new UnsupportedOperationException("S1: core-forms-framework — ConfirmForms.actualize");
    }

    /**
     * §6.26 применение «что-если»; пусто, если режим выключен.
     *
     * @param state состояние
     * @return подтверждение или пусто
     */
    public static Optional<Confirmation> applyWhatIf(AppState state) {
        throw new UnsupportedOperationException("S1: core-forms-framework — ConfirmForms.applyWhatIf");
    }

    /**
     * §6.27 очистка снимков; статус {@code status.msg.snapshotsCleared}.
     *
     * @return подтверждение
     */
    public static Confirmation clearSnapshots() {
        throw new UnsupportedOperationException("S1: core-forms-framework — ConfirmForms.clearSnapshots");
    }
}
