package ru.cashprediction.core.forecast.service;

import java.time.LocalDate;
import java.util.Objects;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Plan;

/**
 * Неизменяемые входные данные одного расчёта, без документа, UI, файлов и системных часов.
 *
 * <p>Суммы в {@code Plan} и {@code WhatIf} представлены {@code Money}: знаковый long минимальных единиц
 * с двумя десятичными знаками в диапазоне {@code [-Long.MAX_VALUE, Long.MAX_VALUE]};
 * {@code Long.MIN_VALUE} запрещён самим Money. Коэффициенты остаются BigDecimal,
 * а округление и проверка переполнения принадлежат существующему алгоритму. Запрос не вводит бизнес-валидацию
 * плана: повреждённый файл по-прежнему можно открыть и отдельно диагностировать.</p>
 *
 * @param plan неизменяемый снимок плана с защитными копиями списков
 * @param whatIf параметры расчёта; null заменяется на NONE, как в прежнем движке
 * @param today явная дата расчёта, обязательная и независимая от часов процесса
 * @param includeSkipped включать ли пропущенные события в строки результата
 */
public record ForecastRequest(Plan plan, WhatIf whatIf, LocalDate today, boolean includeSkipped) {
    /** Проверяет обязательные ссылки, сохраняя терпимость к бизнес-ошибкам в данных плана. */
    public ForecastRequest {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(today, "today");
        whatIf = whatIf == null ? WhatIf.NONE : whatIf;
    }
}
