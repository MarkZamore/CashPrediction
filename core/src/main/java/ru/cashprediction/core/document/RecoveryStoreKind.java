package ru.cashprediction.core.document;

import java.util.Locale;
import java.util.Optional;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.markdown.RuFormats;

/**
 * Хранилище снимка сессии, предлагаемое по умолчанию при восстановлении после сбоя
 * (кнопка по умолчанию в диалоге восстановления и {@code RadioMenuItem} «Хранилище по умолчанию»).
 *
 * <p>Подпись {@link #label()} — слово грамматики {@code settings.md}: оно берётся из нелокализуемого ресурса
 * {@link FormatWords}, чтобы файл настроек читался при любом языке интерфейса.</p>
 */
public enum RecoveryStoreKind {
    /** Реестр Windows через {@code java.util.prefs} (узел HKCU). */
    REGISTRY,
    /** Файл {@code CashMemory/session-<клиент>.xml}. */
    XML;

    /** @return подпись для файла настроек и меню: «реестр» / «XML» */
    public String label() {
        // Слово ищется при каждом вызове, а не в конструкторе: ошибка ресурса не ломает загрузку перечисления.
        return switch (this) {
            case REGISTRY -> FormatWords.get("settings.recoveryStore.registry");
            case XML -> FormatWords.get("settings.recoveryStore.xml");
        };
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
            if (t.equals(RuFormats.normalize(kind.label())) || t.equals(kind.name().toLowerCase(Locale.ROOT))) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
