package ru.cashprediction.core.forecast.service;

import java.time.DateTimeException;
import java.util.Objects;

/**
 * Закрытые виды ожидаемых ошибок расчёта без UI-текста или разбора сообщения для определения причины.
 *
 * <p>Конкретные исключения сохраняют прежние базовые типы: IllegalStateException, ArithmeticException,
 * IllegalArgumentException и DateTimeException. Существующие обработчики продолжают работать;
 * сообщение берётся только из исходной причины. Для общей классификации можно проверить
 * instanceof ForecastFailure и прочитать kind.</p>
 */
public sealed interface ForecastFailure
        permits ForecastFailure.LimitExceeded, ForecastFailure.AmountOverflow, ForecastFailure.AmountOutOfRange,
                ForecastFailure.DateRangeExceeded {
    /** Машинная причина ошибки; не является текстом интерфейса. */
    enum Kind {
        /** Превышен существующий предел горизонта или генерации дат. */
        LIMIT_EXCEEDED,
        /** Переполнение точной денежной арифметики. */
        AMOUNT_OVERFLOW,
        /** Результат равен Long.MIN_VALUE, который не входит в диапазон Money. */
        AMOUNT_OUT_OF_RANGE,
        /** Календарная операция вышла за поддерживаемый диапазон дат. */
        DATE_RANGE_EXCEEDED
    }

    /** Недопустимая граница Money с прежним базовым типом IllegalArgumentException. */
    final class AmountOutOfRange extends IllegalArgumentException implements ForecastFailure {
        private static final long serialVersionUID = 1L;

        /** @param cause исходная ошибка диапазона Money с неизменённым сообщением */
        public AmountOutOfRange(IllegalArgumentException cause) {
            super(Objects.requireNonNull(cause, "cause").getMessage(), cause);
        }

        /** {@inheritDoc} */
        @Override
        public Kind kind() {
            return Kind.AMOUNT_OUT_OF_RANGE;
        }
    }

    /** @return машинный вид ошибки, независимый от локализованного сообщения */
    Kind kind();

    /** Превышение ресурсов с прежним базовым типом IllegalStateException. */
    final class LimitExceeded extends IllegalStateException implements ForecastFailure {
        private static final long serialVersionUID = 1L;

        /** @param cause исходное исключение существующего движка с неизменённым сообщением */
        public LimitExceeded(IllegalStateException cause) {
            super(Objects.requireNonNull(cause, "cause").getMessage(), cause);
        }

        /** {@inheritDoc} */
        @Override
        public Kind kind() {
            return Kind.LIMIT_EXCEEDED;
        }
    }

    /** Переполнение сумм с прежним базовым типом ArithmeticException. */
    final class AmountOverflow extends ArithmeticException implements ForecastFailure {
        private static final long serialVersionUID = 1L;

        /** @param cause исходная ошибка точной арифметики, без замены её текста */
        public AmountOverflow(ArithmeticException cause) {
            super(Objects.requireNonNull(cause, "cause").getMessage());
            initCause(cause);
        }

        /** {@inheritDoc} */
        @Override
        public Kind kind() {
            return Kind.AMOUNT_OVERFLOW;
        }
    }

    /** Выход за диапазон дат с прежним базовым типом DateTimeException. */
    final class DateRangeExceeded extends DateTimeException implements ForecastFailure {
        private static final long serialVersionUID = 1L;

        /** @param cause исходная ошибка календарной операции, без замены её текста */
        public DateRangeExceeded(DateTimeException cause) {
            super(Objects.requireNonNull(cause, "cause").getMessage(), cause);
        }

        /** {@inheritDoc} */
        @Override
        public Kind kind() {
            return Kind.DATE_RANGE_EXCEEDED;
        }
    }
}
