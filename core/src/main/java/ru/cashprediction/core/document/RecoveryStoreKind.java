package ru.cashprediction.core.document;

import java.util.Locale;
import java.util.Optional;
import ru.cashprediction.core.markdown.RuFormats;

/**
 * Хранилище снимка сессии, предлагаемое по умолчанию при восстановлении после сбоя
 * (кнопка по умолчанию в диалоге восстановления и {@code RadioMenuItem} «Хранилище по умолчанию»).
 */
public enum RecoveryStoreKind {
    /** Реестр Windows через {@code java.util.prefs} (узел HKCU). */
    REGISTRY("реестр"),
    /** Файл {@code CashMemory/session-<клиент>.xml}. */
    XML("XML");

    private final String label;

    RecoveryStoreKind(String label) {
        this.label = label;
    }

    /** @return подпись для файла настроек и меню: «реестр» / «XML» */
    public String label() {
        return label;
    }

    /**
     * Распознаёт хранилище по подписи или имени константы (регистр не важен).
     *
     * @param text «реестр», «XML», «registry»
     * @return хранилище или пустое значение, если текст не распознан
     */
    public static Optional<RecoveryStoreKind> parse(String text) {
        String t = RuFormats.normalize(text);
        for (RecoveryStoreKind kind : values()) {
            if (t.equals(RuFormats.normalize(kind.label)) || t.equals(kind.name().toLowerCase(Locale.ROOT))) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
