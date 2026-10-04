package ru.cashprediction.core.web.reconnect;

/** Неизменяемый ключ переподключения; строковое представление никогда не раскрывает ключ. */
public final class ReconnectCredential {
    private final String id;
    private final String keyHex;

    /** Создаёт проверенные идентификатор копии и ключ в канонической шестнадцатеричной записи. */
    public ReconnectCredential(String id, String keyHex) {
        if (id == null || !id.matches("[0-9a-f]{32}")
                || keyHex == null || !keyHex.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid reconnect credential");
        this.id = id;
        this.keyHex = keyHex;
    }

    /** Возвращает идентификатор портативной копии. */
    public String id() { return id; }

    /** Возвращает секрет только для защищённого bootstrap и вычисления доказательств. */
    public String keyHex() { return keyHex; }

    /** Возвращает диагностическое представление без секрета. */
    @Override public String toString() { return "ReconnectCredential[id=" + id + ", key=redacted]"; }
}
