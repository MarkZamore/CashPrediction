package ru.cashprediction.core.session.codec;

import java.util.Optional;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SnapshotSchema;

/**
 * Содержимое файла сессии целиком: маркер сеанса и снимок, каждый из которых может отсутствовать.
 *
 * <p>XML- и Markdown-хранилища держат маркер и снимок в одном файле. Сразу после старта в файле
 * есть только маркер; у старого файла, записанного до {@code markDirty}, — только снимок. Эта
 * запись позволяет кодекам читать и писать все такие состояния, а хранилищам — менять маркер,
 * не трогая снимок.</p>
 *
 * <p>Запись неизменяема и потокобезопасна.</p>
 *
 * @param client   клиент, которому принадлежит файл
 * @param marker   маркер сеанса или {@code null}
 * @param snapshot снимок или {@code null}
 */
public record SessionDocument(String client, SessionMarker marker, SessionSnapshot snapshot) {

    /** Проверяет идентификатор клиента. */
    public SessionDocument {
        SnapshotSchema.requireClient(client);
    }

    /**
     * Маркер как {@link Optional}.
     *
     * @return маркер или пусто
     */
    public Optional<SessionMarker> findMarker() {
        return Optional.ofNullable(marker);
    }

    /**
     * Снимок как {@link Optional}.
     *
     * @return снимок или пусто
     */
    public Optional<SessionSnapshot> findSnapshot() {
        return Optional.ofNullable(snapshot);
    }

    /**
     * Возвращает документ с другим маркером и тем же снимком.
     *
     * @param newMarker новый маркер или {@code null}
     * @return новый документ
     */
    public SessionDocument withMarker(SessionMarker newMarker) {
        return new SessionDocument(client, newMarker, snapshot);
    }

    /**
     * Возвращает документ с другим снимком и тем же маркером.
     *
     * @param newSnapshot новый снимок или {@code null}
     * @return новый документ
     */
    public SessionDocument withSnapshot(SessionSnapshot newSnapshot) {
        return new SessionDocument(client, marker, newSnapshot);
    }
}
