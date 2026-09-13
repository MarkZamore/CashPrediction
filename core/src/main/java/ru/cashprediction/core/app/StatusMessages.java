package ru.cashprediction.core.app;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.ui.view.status.StatusLevel;

/**
 * Сообщения сегмента «Сообщение» строки состояния (спецификация v2, §5.4 п. 5, §8.2) — состояние контроллера.
 *
 * <p><b>Что показывается</b> ({@link #visible(Instant)}), по убыванию приоритета: подсказка пункта меню под
 * указателем; последнее временное сообщение, если с его показа прошло меньше 10 с
 * ({@code DesignTokens.STATUS_MESSAGE_MS}); первое постоянное сообщение ({@code status.msg.settingsFailed},
 * {@code status.msg.forecastFailed}), которое держится, пока причина не исчезнет. Истечение 10 с контроллер
 * отслеживает таймером планировщика и перерисовывает часть STATUS.</p>
 *
 * @param message    последнее временное сообщение или {@code null}
 * @param hoverTip   подсказка пункта меню под указателем или пустая строка
 * @param persistent постоянные сообщения по id причины ({@code settingsFailed}, {@code forecastFailed}) в порядке появления
 */
public record StatusMessages(Message message, String hoverTip, Map<String, Message> persistent) {

    /** Сообщений нет. */
    public static final StatusMessages EMPTY = new StatusMessages(null, "", Map.of());

    /**
     * Одно сообщение.
     *
     * @param text      готовый текст из каталога
     * @param level     уровень (цвет)
     * @param expiresAt момент исчезновения или {@code null} для постоянного сообщения
     */
    public record Message(String text, StatusLevel level, Instant expiresAt) {
        /** Проверяет поля. */
        public Message {
            text = Objects.requireNonNullElse(text, "");
            Objects.requireNonNull(level, "level");
        }
    }

    /** Заменяет {@code null} и копирует карту. */
    public StatusMessages {
        hoverTip = Objects.requireNonNullElse(hoverTip, "");
        persistent = persistent == null ? Map.of() : Map.copyOf(persistent);
    }

    /**
     * Показывает временное сообщение на 10 секунд.
     *
     * @param text  текст
     * @param level уровень
     * @param now   текущий момент ({@code AppClock.now()})
     * @return новое состояние
     */
    public StatusMessages show(String text, StatusLevel level, Instant now) {
        throw new UnsupportedOperationException("S2: core-app-file — StatusMessages.show");
    }

    /**
     * Устанавливает или снимает подсказку пункта меню.
     *
     * @param tip подсказка или пустая строка / {@code null}
     * @return новое состояние
     */
    public StatusMessages hover(String tip) {
        throw new UnsupportedOperationException("S2: core-app-file — StatusMessages.hover");
    }

    /**
     * Устанавливает постоянное сообщение причины.
     *
     * @param causeId id причины ({@code settingsFailed}, {@code forecastFailed})
     * @param text    текст
     * @param level   уровень
     * @return новое состояние
     */
    public StatusMessages withPersistent(String causeId, String text, StatusLevel level) {
        throw new UnsupportedOperationException("S2: core-app-file — StatusMessages.withPersistent");
    }

    /**
     * Снимает постоянное сообщение, когда причина исчезла.
     *
     * @param causeId id причины
     * @return новое состояние
     */
    public StatusMessages withoutPersistent(String causeId) {
        throw new UnsupportedOperationException("S2: core-app-file — StatusMessages.withoutPersistent");
    }

    /**
     * Что показывать сейчас (правила — в описании класса).
     *
     * @param now текущий момент
     * @return сообщение или пусто; подсказка меню возвращается как сообщение уровня INFO без срока
     */
    public Optional<Message> visible(Instant now) {
        throw new UnsupportedOperationException("S2: core-app-file — StatusMessages.visible");
    }
}
