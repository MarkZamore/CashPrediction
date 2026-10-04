package ru.cashprediction.core.ui.selftest.paint;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Неизменяемое намерение опыта S5, без ожидаемых пикселей и цветов.
 * Состояния карточек задают ввод; фактическое состояние читается сборщиком отдельно.
 * Дедлайн относится к монотонным часам клиента, а не к часам другого процесса.
 * @param runId идентификатор запуска
 * @param captureId уникальный идентификатор попытки
 * @param commandNumber номер команды
 * @param scenario безопасное имя сценария
 * @param step безопасное имя шага
 * @param attempt номер попытки, начиная с единицы
 * @param planSha256 хеш сохранённого плана
 * @param cardStates намерения для карточек
 * @param deadlineNanos дедлайн на часах клиента
 */
public record PaintCaptureRequest(UUID runId, UUID captureId, int commandNumber, String scenario,
                                  String step, int attempt, String planSha256,
                                  Map<String, CardState> cardStates, long deadlineNanos) {
    /** Проверяет идентичность и копирует намерения. */
    public PaintCaptureRequest {
        Objects.requireNonNull(runId); Objects.requireNonNull(captureId);
        PaintObservation.nonnegative(commandNumber);
        PaintObservation.component(scenario); PaintObservation.component(step);
        PaintObservation.require(attempt > 0, "attempt"); PaintObservation.hash(planSha256);
        cardStates = Map.copyOf(cardStates);
        PaintObservation.require(cardStates.size() <= PaintObservation.MAX_CARDS, "cardStates limit");
        cardStates.keySet().forEach(PaintObservation::text);
        long active = cardStates.values().stream().filter(s -> s != CardState.NORMAL).count();
        PaintObservation.require(active <= 1, "one target card per experiment");
    }

    /** Планируемое состояние одной карточки, не свидетельство её отрисовки. */
    public enum CardState { NORMAL, HOVER, FOCUS }
}
