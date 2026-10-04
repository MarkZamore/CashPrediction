package ru.cashprediction.core.forecast.service;

import java.time.DateTimeException;
import java.util.Objects;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;

/**
 * Служба версии 1 над существующим ForecastEngine, без изменения алгоритма, состояния или кэша.
 *
 * <p>Экземпляр потокобезопасен. DEFAULT нужен только обратносовместимым конструкторам; явное внедрение
 * позволяет приложению или тесту передать один собственный экземпляр документу и всем его формам.</p>
 */
public final class EngineForecastService implements ForecastService {
    /** Общая неизменяемая служба для прежних конструкторов документа и контекста формы. */
    public static final EngineForecastService DEFAULT = new EngineForecastService();

    /** Создаёт независимый экземпляр службы без состояния. */
    public EngineForecastService() {
    }

    /** {@inheritDoc} */
    @Override
    public Forecast calculate(ForecastRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return ForecastEngine.forecast(request.plan(), request.whatIf(), request.today(), request.includeSkipped());
        } catch (ArithmeticException cause) {
            throw new ForecastFailure.AmountOverflow(cause);
        } catch (DateTimeException cause) {
            throw new ForecastFailure.DateRangeExceeded(cause);
        } catch (IllegalArgumentException cause) {
            // После создания запроса текущий движок бросает этот тип только при проверке границы в Money.
            throw new ForecastFailure.AmountOutOfRange(cause);
        } catch (IllegalStateException cause) {
            // Два существующих throw этого типа: предел дней движка и предел дат генератора.
            throw new ForecastFailure.LimitExceeded(cause);
        }
    }
}
