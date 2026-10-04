package ru.cashprediction.core.session;

import java.util.Objects;
import java.util.Optional;

/**
 * Граница чтения данных восстановления, не содержащая окон, callback, Path или Throwable.
 * Чтение не меняет снимки и маркеры; каждый явный вызов перечитывает выбранное хранилище.
 * Пустой результат означает отсутствие снимка, а не повреждение. Автоматических повторов нет.
 * Remote адаптер обязан вернуть отказ при неуспехе чтения; открытие окон остаётся локальным UI.
 */
@FunctionalInterface
public interface RecoverySnapshots {
    /** Типизированная диагностическая информация. @param code категория @param detail исходное описание */
    record Problem(SessionStoreException.Code code, String detail) {
        /** Проверяет обязательную категорию, нормализует отсутствие диагностики. */
        public Problem {
            Objects.requireNonNull(code, "code");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    /**
     * Данные прочитанного снимка либо отказ, либо отсутствие снимка.
     * @param snapshot прочитанные неизменяемые данные
     * @param problem ожидаемый отказ
     */
    record Result(Optional<SessionSnapshot> snapshot, Optional<Problem> problem) {
        /** Запрещает неоднозначный результат с данными и ошибкой одновременно. */
        public Result {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(problem, "problem");
            if (snapshot.isPresent() && problem.isPresent()) throw new IllegalArgumentException("AMBIGUOUS_RECOVERY_RESULT");
        }
    }

    /**
     * Читает снимок выбранной службы без неявного переключения на другое хранилище.
     * Повреждение одного хранилища не запрещает независимое чтение другого.
     * @param storeId непрозрачный идентификатор подключённого хранилища
     * @return данные, отсутствие снимка либо машинный отказ
     */
    Result read(String storeId);
}
