package ru.cashprediction.core.app;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.ui.view.status.StatusLevel;
import ru.cashprediction.core.ui.token.DesignTokens;

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
        Objects.requireNonNull(now, "now");
        return new StatusMessages(new Message(text, Objects.requireNonNull(level, "level"),
                now.plusMillis(DesignTokens.STATUS_MESSAGE_MS)), hoverTip, persistent);
    }

    /**
     * Устанавливает или снимает подсказку пункта меню.
     *
     * @param tip подсказка или пустая строка / {@code null}
     * @return новое состояние
     */
    public StatusMessages hover(String tip) {
        return new StatusMessages(message, tip, persistent);
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
        Objects.requireNonNull(causeId, "causeId");
        java.util.LinkedHashMap<String, Message> updated = new java.util.LinkedHashMap<>(persistent);
        updated.put(causeId, new Message(text, Objects.requireNonNull(level, "level"), null));
        return new StatusMessages(message, hoverTip, updated);
    }

    /**
     * Снимает постоянное сообщение, когда причина исчезла.
     *
     * @param causeId id причины
     * @return новое состояние
     */
    public StatusMessages withoutPersistent(String causeId) {
        if (causeId == null || !persistent.containsKey(causeId)) {
            return this;
        }
        java.util.LinkedHashMap<String, Message> updated = new java.util.LinkedHashMap<>(persistent);
        updated.remove(causeId);
        return new StatusMessages(message, hoverTip, updated);
    }

    /**
     * Что показывать сейчас (правила — в описании класса).
     *
     * @param now текущий момент
     * @return сообщение или пусто; подсказка меню возвращается как сообщение уровня INFO без срока
     */
    public Optional<Message> visible(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!hoverTip.isBlank()) {
            return Optional.of(new Message(hoverTip, StatusLevel.INFO, null));
        }
        if (message != null && (message.expiresAt() == null || now.isBefore(message.expiresAt()))) {
            return Optional.of(message);
        }
        return persistent.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).findFirst();
    }
}
