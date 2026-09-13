package ru.cashprediction.core.session.codec;

import ru.cashprediction.core.session.SessionSnapshot;

/**
 * Кодек снимка сессии: превращает {@link SessionSnapshot} в представление хранилища и обратно.
 *
 * <p>Реализации: {@link JsonSnapshotCodec} (реестр и HTTP), {@link XmlSnapshotCodec} (файл
 * {@code session-<клиент>.xml}), {@link MarkdownSnapshotCodec} (файл {@code web-session.md}).
 * Каждая обязана без потерь переносить кириллицу, кавычки, {@code < > &}, вертикальную черту,
 * переводы строк, пустые карты и отсутствующие ({@code null}) границы окон — это проверяет
 * {@code SnapshotCodecsTest}.</p>
 *
 * <p>Реализации не имеют состояния и потокобезопасны.</p>
 *
 * @param <T> тип представления (для всех текущих кодеков — {@link String})
 */
public interface SnapshotCodec<T> {

    /**
     * Название формата для сообщений.
     *
     * @return например «JSON»
     */
    String formatName();

    /**
     * Кодирует снимок.
     *
     * @param snapshot снимок
     * @return представление снимка
     */
    T encode(SessionSnapshot snapshot);

    /**
     * Разбирает снимок.
     *
     * @param encoded представление снимка
     * @return снимок
     * @throws SnapshotFormatException если данные повреждены или записаны более новой версией; сообщение на русском
     */
    SessionSnapshot decode(T encoded) throws SnapshotFormatException;
}
