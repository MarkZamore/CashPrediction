package ru.cashprediction.core.session;

import java.time.Instant;
import java.util.Optional;

/**
 * Хранилище маркера сеанса и снимка сессии одного клиента.
 *
 * <p>Реализации: реестр Windows ({@code RegistrySessionStore}), XML-файл ({@code XmlSessionStore}),
 * Markdown-файл web-сервера ({@code MarkdownSessionStore}). Рекордер пишет в несколько хранилищ
 * одновременно, а при следующем запуске пользователь выбирает, из какого восстанавливаться.</p>
 *
 * <p>Контракт ошибок: {@link #save} и {@link #load} бросают {@link SessionStoreException};
 * {@link #markDirty}, {@link #markClean} и {@link #clear} не бросают никогда — они вызываются
 * при старте и выходе, где исключение только помешало бы. Их неудача отражается в
 * {@link #lastError()} (и, для реестра, в {@link #isAvailable()}).</p>
 *
 * <p>Потокобезопасность: реализации обязаны синхронизировать {@code save}, {@code markDirty},
 * {@code markClean} и {@code clear}, потому что фоновая запись рекордера может совпасть по
 * времени с синхронной записью из обработчика сбоя.</p>
 */
public interface SessionStore {

    /**
     * Машинный идентификатор хранилища.
     *
     * @return {@code registry}, {@code xml} или {@code server}
     */
    String id();

    /**
     * Название для пользователя.
     *
     * @return например «Реестр Windows», «XML-файл», «Сервер»
     */
    String title();

    /**
     * Можно ли пользоваться хранилищем в этом процессе.
     *
     * @return {@code false}, если хранилище недоступно до перезапуска (например, реестр запрещён политикой)
     */
    boolean isAvailable();

    /**
     * Причина недоступности.
     *
     * @return сообщение на русском или пустая строка, если хранилище доступно
     */
    String unavailableReason();

    /**
     * Записывает маркер «сеанс идёт». Существующий снимок обязан сохраниться: маркер ставится
     * при старте, когда пользователь, возможно, ещё не решил, восстанавливаться ли.
     *
     * @param marker маркер {@code running}
     */
    void markDirty(SessionMarker marker);

    /**
     * Отмечает корректное завершение сеанса; снимок сохраняется (его можно посмотреть через меню).
     */
    void markClean();

    /**
     * Читает маркер.
     *
     * @return маркер или пусто, если его нет, он повреждён или хранилище недоступно
     */
    Optional<SessionMarker> readMarker();

    /**
     * Записывает снимок, не меняя маркер.
     *
     * @param snapshot снимок
     * @throws SessionStoreException если запись не удалась; сообщение на русском
     */
    void save(SessionSnapshot snapshot) throws SessionStoreException;

    /**
     * Читает снимок.
     *
     * @return снимок или пусто, если снимка нет
     * @throws SessionStoreException если данные повреждены или не читаются; сообщение на русском
     */
    Optional<SessionSnapshot> load() throws SessionStoreException;

    /**
     * Момент последнего сохранённого снимка, без полного разбора, если это возможно.
     *
     * @return момент или пусто, если снимка нет или он не читается
     */
    Optional<Instant> lastSavedAt();

    /**
     * Удаляет маркер и снимок (пункт «Не восстанавливать» и «Очистить снимки»).
     */
    void clear();

    /**
     * Последняя ошибка операции, которая не бросает исключений ({@link #markDirty}, {@link #markClean},
     * {@link #clear}); сбрасывается следующей успешной операцией.
     *
     * @return сообщение на русском или пусто
     */
    default Optional<String> lastError() {
        return Optional.empty();
    }
}
