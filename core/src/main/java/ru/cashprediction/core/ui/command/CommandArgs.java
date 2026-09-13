package ru.cashprediction.core.ui.command;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Аргументы вызова команды: к какой строке, дате или карточке она относится.
 *
 * <p>Пустые строковые значения — пустая строка, а не {@code null}: так аргументы одинаково передаются
 * в JSON web-протокола. Какие поля заполнены, описано у каждой константы {@link CommandId}.</p>
 *
 * @param rowId  идентификатор строки таблицы ({@code r2@2026-10-01}, {@code t1}, {@code start}, …) или пустая строка
 * @param date   дата (карточка, график, «Добавить разовую на …») или {@code null}
 * @param cardId идентификатор карточки сводки ({@code now}, {@code m3}, …) или пустая строка
 * @param key    дополнительный ключ (номер пункта «Недавние», id пункта меню) или пустая строка
 * @param value  дополнительное значение (путь недавнего файла) или пустая строка
 */
public record CommandArgs(String rowId, LocalDate date, String cardId, String key, String value) {

    /** Вызов без аргументов. */
    public static final CommandArgs NONE = new CommandArgs("", null, "", "", "");

    /** Заменяет {@code null}-строки пустыми. */
    public CommandArgs {
        rowId = Objects.requireNonNullElse(rowId, "");
        cardId = Objects.requireNonNullElse(cardId, "");
        key = Objects.requireNonNullElse(key, "");
        value = Objects.requireNonNullElse(value, "");
    }

    /**
     * Аргументы строки таблицы.
     *
     * @param rowId идентификатор строки
     * @return аргументы
     */
    public static CommandArgs row(String rowId) {
        return new CommandArgs(rowId, null, "", "", "");
    }

    /**
     * Аргументы строки с датой (например, «Добавить разовую на dd.MM.yyyy…» из меню строки).
     *
     * @param rowId идентификатор строки
     * @param date  дата
     * @return аргументы
     */
    public static CommandArgs rowAndDate(String rowId, LocalDate date) {
        return new CommandArgs(rowId, date, "", "", "");
    }

    /**
     * Аргументы даты (график).
     *
     * @param date дата или {@code null}, если указатель вне области построения
     * @return аргументы
     */
    public static CommandArgs date(LocalDate date) {
        return new CommandArgs("", date, "", "", "");
    }

    /**
     * Аргументы карточки сводки.
     *
     * @param cardId идентификатор карточки
     * @param date   дата карточки или {@code null}
     * @return аргументы
     */
    public static CommandArgs card(String cardId, LocalDate date) {
        return new CommandArgs("", date, cardId, "", "");
    }

    /**
     * Аргументы «ключ — значение».
     *
     * @param key   ключ
     * @param value значение
     * @return аргументы
     */
    public static CommandArgs keyValue(String key, String value) {
        return new CommandArgs("", null, "", key, value);
    }
}
