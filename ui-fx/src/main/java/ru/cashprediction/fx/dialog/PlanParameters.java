package ru.cashprediction.fx.dialog;

import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;

import java.time.LocalDate;

/**
 * Результат диалога «Параметры плана»: всё, что относится к плану целиком, кроме операций.
 *
 * <p>Применяется к <i>текущему на момент подтверждения</i> плану через {@link #applyTo(Plan)}: пока диалог
 * был открыт, пользователь мог, например, отменить другое изменение, и перезаписывать план целиком
 * копией из момента открытия было бы ошибкой. Запись неизменяема.</p>
 *
 * @param name         имя плана
 * @param currency     валюта
 * @param startDate    дата начала
 * @param startBalance баланс на дату начала
 * @param horizon      горизонт прогноза
 * @param cushion      подушка безопасности
 * @param note         заметка
 * @param goal         цель накопления или {@code null}
 */
public record PlanParameters(String name, String currency, LocalDate startDate, Money startBalance, Horizon horizon,
                             Money cushion, String note, Goal goal) {

    /**
     * Переносит параметры в план, сохраняя правила, разовые операции, корректировки и нераспознанные блоки.
     *
     * @param plan текущий план
     * @return план с новыми параметрами
     */
    public Plan applyTo(Plan plan) {
        return plan.withName(name)
                .withCurrency(currency)
                .withStart(startDate, startBalance)
                .withHorizon(horizon)
                .withCushion(cushion)
                .withNote(note)
                .withGoal(goal);
    }
}
