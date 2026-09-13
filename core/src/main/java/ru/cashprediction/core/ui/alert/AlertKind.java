package ru.cashprediction.core.ui.alert;

/**
 * Тип сообщения (спецификация v2, §6.0 «Каркас сообщения»): стандартные значки инструмента; web — ℹ ⚠ ✖ ? в круге
 * 30 px. JavaFX {@code Alert.AlertType} → Swing {@code JOptionPane} тип → Web класс значка.
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum AlertKind {

    /** Сведения: заголовок окна по умолчанию «CashPrediction», web-значок ℹ. */
    INFORMATION("ℹ"),
    /** Предупреждение: «CashPrediction», ⚠. */
    WARNING("⚠"),
    /** Ошибка: «CashPrediction — ошибка», ✖. */
    ERROR("✖"),
    /** Подтверждение: ?. */
    CONFIRMATION("?");

    private final String webGlyph;

    AlertKind(String webGlyph) {
        this.webGlyph = webGlyph;
    }

    /** @return значок web-клиента из {@code DesignTokens.GLYPHS} */
    public String webGlyph() {
        return webGlyph;
    }
}
