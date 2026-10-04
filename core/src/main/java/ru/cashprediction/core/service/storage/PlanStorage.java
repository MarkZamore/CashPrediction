package ru.cashprediction.core.service.storage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.model.Plan;

/**
 * Владелец сохранённых планов в модульном монолите. Ссылки и версии непрозрачны для потребителя;
 * контракт не содержит путей, файловых атрибутов, документа, интерфейса пользователя или транспорта.
 * Запись и переименование обязательно проверяют ожидаемую версию до изменения данных.
 */
public interface PlanStorage {
    /** Непрозрачная ссылка на сохранённый план. @param token значение, которое выдаёт адаптер */
    record Reference(String token) {
        /** Проверяет наличие токена. */
        public Reference { Objects.requireNonNull(token, "token"); }
    }

    /** Непрозрачная ссылка на коллекцию планов. @param token значение адаптера */
    record Collection(String token) {
        /** Проверяет наличие токена. */
        public Collection { Objects.requireNonNull(token, "token"); }
    }

    /** Непрозрачная версия данных, сравниваемая только на равенство. @param token значение адаптера */
    record Version(String token) {
        /** Ожидание отсутствующего плана: разрешает только создание, а не перезапись. */
        public static final Version ABSENT = new Version("absent");
        /** Проверяет наличие токена. */
        public Version { Objects.requireNonNull(token, "token"); }
    }

    /** Машинные категории ошибок, независимые от способа хранения и текстов интерфейса. */
    enum Code { MISSING, CONFLICT, CORRUPT, IO_ERROR }

    /** Причина конфликта для выбора между повторным подтверждением и коллизией имени. */
    enum Conflict { NONE, VERSION_CHANGED, NAME_EXISTS }

    /**
     * Структурированная ошибка.
     * @param code категория
     * @param conflict причина
     * @param detail подробности адаптера
     */
    record Problem(Code code, Conflict conflict, String detail) {
        /** Проверяет категорию и причину, нормализует отсутствующие подробности. */
        public Problem {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(conflict, "conflict");
            detail = Objects.requireNonNullElse(detail, "");
            if ((code == Code.CONFLICT) != (conflict != Conflict.NONE)) {
                throw new IllegalArgumentException("Conflict category mismatch");
            }
        }
    }

    /**
     * Неизменяемое описание элемента списка.
     * @param reference ссылка
     * @param version версия
     * @param name имя
     * @param modifiedAt время
     */
    record Entry(Reference reference, Version version, String name, Instant modifiedAt) {
        /** Проверяет обязательные поля. */
        public Entry {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(modifiedAt, "modifiedAt");
        }
    }

    /**
     * Неизменяемый прочитанный снимок.
     * @param reference ссылка
     * @param version версия
     * @param plan план
     * @param diagnostics замечания
     */
    record Snapshot(Reference reference, Version version, Plan plan, List<Diagnostic> diagnostics) {
        /** Проверяет обязательные поля и защищает список диагностик копией. */
        public Snapshot {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(plan, "plan");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * Квитанция изменения данных без повторного чтения.
     * @param reference итоговая ссылка
     * @param version версия записанных данных
     */
    record Stored(Reference reference, Version version) {
        /** Проверяет обязательные поля. */
        public Stored {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(version, "version");
        }
    }

    /**
     * Ровно один исход операции: значение либо структурированная ошибка.
     * @param <T> тип неизменяемого результата
     * @param value значение
     * @param problem ошибка
     */
    record Result<T>(T value, Problem problem) {
        /** Не допускает отсутствующий или неоднозначный исход. */
        public Result {
            if ((value == null) == (problem == null)) throw new IllegalArgumentException("Exactly one result required");
        }
        /** @return успешно ли выполнена операция */
        public boolean succeeded() { return problem == null; }
        /** @return значение; ошибка остаётся доступной как структурированный объект в исключении */
        public T requireValue() {
            if (problem != null) throw new PlanStorageException(problem);
            return value;
        }
        /**
         * @param <T> тип результата
         * @param value результат
         * @return успешный исход
         */
        public static <T> Result<T> success(T value) { return new Result<>(Objects.requireNonNull(value), null); }
        /**
         * @param <T> тип результата
         * @param problem ошибка
         * @return неуспешный исход
         */
        public static <T> Result<T> failure(Problem problem) { return new Result<>(null, Objects.requireNonNull(problem)); }
    }

    /**
     * @param collection коллекция
     * @return неизменяемый список описаний либо ошибка чтения
     */
    Result<List<Entry>> list(Collection collection);

    /**
     * Наблюдает версию без разбора содержимого; повреждённый текст тоже можно явно перезаписать.
     * @param reference ссылка
     * @return версия либо ABSENT при отсутствии; ошибка доступа не означает отсутствие
     */
    Result<Version> version(Reference reference);

    /**
     * @param reference ссылка
     * @param today дата умолчаний
     * @param expectedVersion ожидаемая версия либо пусто для первого чтения
     * @return снимок либо ошибка
     */
    Result<Snapshot> read(Reference reference, LocalDate today, Optional<Version> expectedVersion);

    /**
     * @param reference ссылка
     * @param today дата умолчаний
     * @return первый снимок без заданной версии
     */
    default Result<Snapshot> read(Reference reference, LocalDate today) {
        return read(reference, today, Optional.empty());
    }

    /**
     * @param reference ссылка
     * @param plan неизменяемый план
     * @param expectedVersion обязательная версия, включая ABSENT
     * @return квитанция либо ошибка без записи при конфликте
     */
    Result<Stored> write(Reference reference, Plan plan, Version expectedVersion);

    /**
     * Меняет имя сохранённого плана, сохраняя прочее исходное содержимое и не принимая несохранённые правки.
     * @param reference ссылка
     * @param name новое имя
     * @param expectedVersion обязательная версия
     * @return квитанция либо ошибка
     */
    Result<Stored> rename(Reference reference, String name, Version expectedVersion);
}
