package ru.cashprediction.core.session;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Полный снимок сессии: главное окно, план и все открытые окна в порядке их открытия.
 *
 * <p>Один и тот же снимок записывается в реестр (JSON-куски), в XML-файл и, для web-клиента,
 * в Markdown-файл. Порядок окон важен: при восстановлении окна переоткрываются в том же порядке,
 * чтобы вложенные модальные диалоги легли друг на друга так же, как до сбоя.</p>
 *
 * <p>Запись неизменяема и потокобезопасна.</p>
 *
 * @param schemaVersion версия схемы, см. {@link SnapshotSchema#CURRENT}
 * @param savedAt       момент снятия снимка
 * @param client        клиент: {@code fx}, {@code swing} или {@code web}
 * @param main          состояние главного окна
 * @param plan          состояние плана
 * @param windows       открытые окна в порядке регистрации (неизменяемая копия)
 */
public record SessionSnapshot(int schemaVersion, Instant savedAt, String client, MainWindowState main,
                              PlanState plan, List<WindowState> windows) {

    /** Проверяет обязательные части и копирует список окон. */
    public SessionSnapshot {
        SnapshotSchema.requireSupported(schemaVersion);
        Objects.requireNonNull(savedAt, "savedAt");
        SnapshotSchema.requireClient(client);
        main = Objects.requireNonNullElseGet(main, MainWindowState::empty);
        plan = Objects.requireNonNullElse(plan, PlanState.CLEAN);
        windows = windows == null ? List.of() : List.copyOf(windows);
    }

    /**
     * Создаёт снимок текущей версии схемы.
     *
     * @param savedAt момент снятия
     * @param client  клиент
     * @param main    главное окно
     * @param plan    план
     * @param windows открытые окна
     * @return снимок со схемой {@link SnapshotSchema#CURRENT}
     */
    public static SessionSnapshot of(Instant savedAt, String client, MainWindowState main, PlanState plan,
                                     List<WindowState> windows) {
        return new SessionSnapshot(SnapshotSchema.CURRENT, savedAt, client, main, plan, windows);
    }

    /**
     * Возвращает копию с другим моментом снятия.
     *
     * @param time новый момент
     * @return новый снимок
     */
    public SessionSnapshot withSavedAt(Instant time) {
        return new SessionSnapshot(schemaVersion, time, client, main, plan, windows);
    }

    /**
     * Ищет окно по идентификатору.
     *
     * @param windowId идентификатор окна
     * @return состояние окна или пусто
     */
    public Optional<WindowState> window(String windowId) {
        return windows.stream().filter(w -> w.id().equals(windowId)).findFirst();
    }

    /**
     * Сравнивает содержимое снимков без учёта момента снятия: рекордер не пишет повторно снимок,
     * в котором ничего не изменилось.
     *
     * @param other другой снимок, может быть {@code null}
     * @return {@code true}, если всё, кроме {@code savedAt}, совпадает
     */
    public boolean sameContent(SessionSnapshot other) {
        return other != null
                && schemaVersion == other.schemaVersion
                && client.equals(other.client)
                && main.equals(other.main)
                && plan.equals(other.plan)
                && windows.equals(other.windows);
    }
}
