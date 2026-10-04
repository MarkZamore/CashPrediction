package ru.cashprediction.core.update.lifecycle;

import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.update.model.UpdateProblem;
import ru.cashprediction.core.update.model.UpdateProblem.Code;

/**
 * Локальный lifecycle с data-only решениями и диагностикой для будущего адаптера.
 * Повторный вход не повторяет барьер, ready запускает не более одного прохода, close идемпотентен.
 * UNKNOWN не разрешает UI. Сеть и установка остаются у реализации; транспорт не является UI-событием.
 */
public interface UpdateSessionLifecycle extends AutoCloseable {
    /** Решение о запуске UI; BLOCKED не обещает, что помощник уже завершил восстановление. */
    enum Decision { ALLOWED, BLOCKED, CLOSED, UNKNOWN }
    /** Последнее наблюдаемое состояние, не подтверждение native установки. */
    enum Stage { NEW, INACTIVE, ENTERED, PREPARING, PREPARED, NOT_PREPARED, CLOSED, UNKNOWN }

    /** Решение барьера. @param decision решение @param problem необязательная причина отказа */
    record StartupResult(Decision decision, Optional<UpdateProblem> problem) {
        /** Проверяет обязательные данные. */
        public StartupResult { Objects.requireNonNull(decision, "decision"); Objects.requireNonNull(problem, "problem"); }
        /** @return разрешено ли создание UI */
        public boolean allowed() { return decision == Decision.ALLOWED; }
    }

    /** Наблюдаемый снимок lifecycle. @param stage состояние @param problem последняя известная ошибка */
    record Status(Stage stage, Optional<UpdateProblem> problem) {
        /** Проверяет обязательные данные. */
        public Status { Objects.requireNonNull(stage, "stage"); Objects.requireNonNull(problem, "problem"); }
    }

    /** Совместимый фасад клиента. @return разрешено ли создание UI */
    boolean beforeUi();

    /**
     * Выполняет барьер и возвращает DTO; реализация сохраняет собственную политику безопасного запуска.
     * @return явное решение без callback/Throwable/Path
     */
    default StartupResult beforeUiResult() {
        return new StartupResult(beforeUi() ? Decision.ALLOWED : Decision.BLOCKED, Optional.empty());
    }

    /** @return data-only состояние; старый адаптер явно сообщает отсутствие подробного контракта */
    default Status status() { return new Status(Stage.UNKNOWN, Optional.empty()); }

    /** Сообщает готовность UI без второго прохода при повторе. */
    void afterUiReady();

    /** Завершает подготовку один раз; исходная реализация решает политику установки после выхода. */
    @Override void close();
}
