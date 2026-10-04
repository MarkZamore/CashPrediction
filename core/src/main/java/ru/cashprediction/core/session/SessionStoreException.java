package ru.cashprediction.core.session;

import java.util.Objects;

/**
 * Проверяемая ошибка снимка с машинной категорией и исходной диагностикой.
 * Категория не выводится из локализованного сообщения; сохранённая причина остаётся локальной.
 */
public final class SessionStoreException extends Exception {
    /** Категории ожидаемого отказа, независимые от языка и транспорта. */
    public enum Code {
        /** Старый адаптер не указал причину; автоматический повтор не обещается. */ UNSPECIFIED,
        /** Хранилище недоступно в текущем процессе; повтор без изменения условий не обещается. */ UNAVAILABLE,
        /** Данные повреждены; повтор чтения не исправляет исходные байты. */ CORRUPT,
        /** Ввод-вывод не завершился; вызывающий может явно повторить чтение. */ IO_ERROR,
        /** Запрошенное хранилище не подключено к службе. */ UNKNOWN_STORE
    }

    private final Code code;

    /** Сохраняет совместимость адаптеров без машинной категории. @param message исходная диагностика */
    public SessionStoreException(String message) { super(message); this.code = Code.UNSPECIFIED; }

    /** Сохраняет исходную причину старого адаптера. @param message диагностика @param cause причина */
    public SessionStoreException(String message, Throwable cause) { super(message, cause); this.code = Code.UNSPECIFIED; }

    /** Создаёт типизированный отказ. @param code категория @param message исходная диагностика */
    public SessionStoreException(Code code, String message) { super(message); this.code = Objects.requireNonNull(code, "code"); }

    /**
     * Создаёт типизированный отказ без потери сообщения и причины.
     * @param code машинная категория
     * @param message исходная диагностика
     * @param cause локальная причина, которая не является DTO службы
     */
    public SessionStoreException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** @return машинная причина отказа, независимая от локализованного текста */
    public Code code() { return code; }
}
