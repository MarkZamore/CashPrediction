package ru.cashprediction.core.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.format.FormatWords;

/**
 * Корректировка одного конкретного события регулярной операции.
 *
 * <p>Пример: правило «Зарплата ежемесячно 5-го, 80 000», но в декабре ожидается 95 000.
 * Вместо разового дохода и отключения правила пользователь корректирует одно событие:
 * {@code Adjustment(r1 @ 2026-12-05, ChangeAmount(95 000))}. Таблица показывает такую строку с отметкой ✎.</p>
 *
 * @param key    какое событие корректируется (правило и номинальная дата)
 * @param action что сделать с событием
 * @param note   заметка, может быть пустой
 */
public record Adjustment(OccurrenceKey key, Action action, String note) {

    /** Проверяет обязательные поля. */
    public Adjustment {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(action, "action");
        note = note == null ? "" : note.strip();
    }

    /**
     * Действие корректировки. Четыре варианта покрывают все жизненные случаи:
     * событие отменилось, изменилась сумма, изменилась дата, изменилось и то и другое.
     */
    public sealed interface Action permits Skip, ChangeAmount, MoveDate, Replace {

        /** @return слово для файла плана из грамматики формата ({@link FormatWords}): пропустить / изменить / перенести / заменить */
        String label();

        /** @return новая сумма, если действие её задаёт */
        default Optional<Money> newAmount() {
            return Optional.empty();
        }

        /** @return новая дата, если действие её задаёт */
        default Optional<LocalDate> newDate() {
            return Optional.empty();
        }
    }

    /** Событие не произойдёт (например, «в октябре продукты не покупаем — в отпуске»). */
    public record Skip() implements Action {
        /** @return слово «пропустить» из грамматики {@link FormatWords} для записи отмены события в файл плана */
        @Override
        public String label() {
            return FormatWords.get("plan.action.skip");
        }
    }

    /**
     * Событие произойдёт в свою дату, но с другой суммой.
     *
     * @param amount новая сумма (положительная)
     */
    public record ChangeAmount(Money amount) implements Action {
        /** Проверяет обязательное поле. */
        public ChangeAmount {
            Objects.requireNonNull(amount, "amount");
        }

        /** @return слово «изменить» из грамматики {@link FormatWords} для записи изменения суммы в файл плана */
        @Override
        public String label() {
            return FormatWords.get("plan.action.change");
        }

        /** @return непустое значение с заданной новой суммой; дата события этим действием не меняется */
        @Override
        public Optional<Money> newAmount() {
            return Optional.of(amount);
        }
    }

    /**
     * Событие произойдёт с обычной суммой, но в другой день. Сдвиг с выходных к новой дате не применяется:
     * пользователь указал точную дату.
     *
     * @param date новая дата
     */
    public record MoveDate(LocalDate date) implements Action {
        /** Проверяет обязательное поле. */
        public MoveDate {
            Objects.requireNonNull(date, "date");
        }

        /** @return слово «перенести» из грамматики {@link FormatWords} для записи переноса события в файл плана */
        @Override
        public String label() {
            return FormatWords.get("plan.action.move");
        }

        /** @return непустое значение с точной новой датой события, к которой не применяется сдвиг с выходных */
        @Override
        public Optional<LocalDate> newDate() {
            return Optional.of(date);
        }
    }

    /**
     * Событие произойдёт в другой день и с другой суммой.
     *
     * @param amount новая сумма (положительная)
     * @param date   новая дата
     */
    public record Replace(Money amount, LocalDate date) implements Action {
        /** Проверяет обязательные поля. */
        public Replace {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(date, "date");
        }

        /** @return слово «заменить» из грамматики {@link FormatWords} для записи изменения суммы и даты в файл плана */
        @Override
        public String label() {
            return FormatWords.get("plan.action.replace");
        }

        /** @return непустое значение с новой суммой события, применяемой вместе с новой датой */
        @Override
        public Optional<Money> newAmount() {
            return Optional.of(amount);
        }

        /** @return непустое значение с точной новой датой события, применяемой вместе с новой суммой */
        @Override
        public Optional<LocalDate> newDate() {
            return Optional.of(date);
        }
    }
}
