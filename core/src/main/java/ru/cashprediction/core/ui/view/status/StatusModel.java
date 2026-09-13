package ru.cashprediction.core.ui.view.status;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Строка состояния: высота 24, фон {@code bg.window}, верхняя рамка {@code border}, {@code font.small}
 * (спецификация v2, §5.4). JavaFX {@code HBox} → Swing {@code JPanel} → Web {@code div[role=status]}.
 *
 * @param segments все восемь сегментов в порядке §5.4 (включая невидимые, чтобы id были стабильны)
 */
public record StatusModel(List<StatusSegment> segments) {

    /** Сегмент «Файл: …». */
    public static final String FILE = "file";
    /** Сегмент несохранённых изменений. */
    public static final String DIRTY = "dirty";
    /** Сегмент «Строк: N». */
    public static final String ROWS = "rows";
    /** Сегмент «Горизонт: …». */
    public static final String HORIZON = "horizon";
    /** Растягивающийся сегмент сообщений и подсказок меню. */
    public static final String MESSAGE = "message";
    /** Сегмент «Δ Что-если включено». */
    public static final String WHAT_IF = "whatIf";
    /** Сегмент «Автосохранение». */
    public static final String AUTOSAVE = "autosave";
    /** Сегмент снимка сеанса. */
    public static final String SESSION = "session";

    /** Порядок сегментов слева направо. */
    public static final List<String> ORDER = List.of(FILE, DIRTY, ROWS, HORIZON, MESSAGE, WHAT_IF, AUTOSAVE, SESSION);

    /** Копирует список. */
    public StatusModel {
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
    }

    /**
     * Сегмент по id.
     *
     * @param id id сегмента
     * @return сегмент или пусто
     */
    public Optional<StatusSegment> find(String id) {
        return segments.stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
