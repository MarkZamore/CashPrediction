package ru.cashprediction.core.ui.command;

import java.util.Objects;

/**
 * Доступность команды и подсказка, почему она отключена.
 *
 * <p>Если отключённую команду вызвали горячей клавишей, ядро показывает в строке состояния текст
 * {@code UiText.get(hintKey)} уровня info (спецификация v2, §3 «Правила», §8.3).</p>
 *
 * @param enabled доступна ли команда
 * @param hintKey ключ каталога {@code status.hint.*}; пустая строка для доступной команды или если подсказки нет
 */
public record Availability(boolean enabled, String hintKey) {

    /** Доступная команда. */
    public static final Availability ENABLED = new Availability(true, "");

    /** Заменяет {@code null} пустой строкой. */
    public Availability {
        hintKey = Objects.requireNonNullElse(hintKey, "");
    }

    /**
     * Отключённая команда с подсказкой.
     *
     * @param hintKey ключ {@code status.hint.*}, например {@code status.hint.noRow}
     * @return недоступность
     */
    public static Availability disabled(String hintKey) {
        return new Availability(false, hintKey);
    }
}
