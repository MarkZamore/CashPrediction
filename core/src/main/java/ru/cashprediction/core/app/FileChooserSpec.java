package ru.cashprediction.core.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Запрос выбора файла (спецификация v2, §6.21; JavaFX {@code FileChooser} → Swing {@code JFileChooser} →
 * Web окно ядра «Выбор файла»). Не восстанавливается после сбоя.
 *
 * <p>Тексты уже готовы (из каталога). Расширение к имени дописывает ядро ({@code FileChooserService}), а не
 * клиент; вопрос о замене существующего файла тоже задаёт ядро, кроме клиентов с
 * {@link ClientProfile#nativeReplacePrompt()}. В дамп попадает именно этот запрос (§10 №12).</p>
 *
 * @param purpose           назначение
 * @param mode              открыть или сохранить
 * @param title             заголовок окна, например «Открыть план из файла»
 * @param filterDescription описание фильтра, например «План CashPrediction (*.md)»
 * @param extensions        допустимые расширения без точки в нижнем регистре, например {@code [md]}
 * @param initialFolder     начальная папка
 * @param initialName       начальное имя файла (для сохранения) или пустая строка
 */
public record FileChooserSpec(Purpose purpose, Mode mode, String title, String filterDescription,
                              List<String> extensions, Path initialFolder, String initialName) {

    /** Назначение выбора (строки таблицы §6.21). */
    public enum Purpose {
        /** «Открыть план из файла», фильтр .md, папка — текущая папка планов. */
        OPEN_PLAN,
        /** «Сохранить план как», фильтр .md, папка файла или CashMemory, имя «{base}.md». */
        SAVE_PLAN_AS,
        /** «Экспорт прогноза в CSV», фильтр .csv, CashMemory, имя «{base}.csv». */
        EXPORT_CSV,
        /** «Сохранить график как PNG», фильтр .png, CashMemory, имя «{base} — график.png». */
        SAVE_PNG,
        /** «Сохранить план из снимка», фильтр .md, CashMemory, имя «{title} (восстановлен).md». */
        SAVE_SNAPSHOT_PLAN
    }

    /** Режим окна выбора. */
    public enum Mode {
        /** Выбор существующего файла. */
        OPEN,
        /** Выбор имени для записи. */
        SAVE
    }

    /** Проверяет поля и копирует список. */
    public FileChooserSpec {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(mode, "mode");
        title = Objects.requireNonNullElse(title, "");
        filterDescription = Objects.requireNonNullElse(filterDescription, "");
        extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
        Objects.requireNonNull(initialFolder, "initialFolder");
        initialName = Objects.requireNonNullElse(initialName, "");
    }
}
