package ru.cashprediction.core.forecast.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import ru.cashprediction.core.forecast.Forecast;

/** Подставная служба: фиксирует реальные запросы потребителей и возвращает заданный тестом ответ. */
final class FakeForecastService implements ForecastService {
    private final List<ForecastRequest> requests = new ArrayList<>();
    private final Function<ForecastRequest, Forecast> response;

    /** Создаёт записывающий адаптер над неизменённым движком для регрессионных проверок. */
    FakeForecastService() {
        this(EngineForecastService.DEFAULT::calculate);
    }

    /** Создаёт службу с управляемым ответом, в том числе с исключением вместо результата. */
    FakeForecastService(Function<ForecastRequest, Forecast> response) {
        this.response = Objects.requireNonNull(response, "response");
    }

    /** {@inheritDoc} */
    @Override
    public Forecast calculate(ForecastRequest request) {
        requests.add(Objects.requireNonNull(request, "request"));
        return Objects.requireNonNull(response.apply(request), "response returned null");
    }

    /** @return независимый неизменяемый снимок запросов в порядке вызовов */
    List<ForecastRequest> requests() {
        return List.copyOf(requests);
    }
}
