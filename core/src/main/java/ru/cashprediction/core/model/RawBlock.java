package ru.cashprediction.core.model;

import java.util.List;
import java.util.Objects;

/**
 * Фрагмент файла плана, который программа не распознала, но обязана сохранить.
 *
 * <p>Файлы CashMemory можно править вручную. Если пользователь дописал свою секцию «## Мои заметки»
 * или непонятный ключ в «Параметры», при следующем сохранении этот текст не должен исчезнуть.
 * Читатель складывает такие строки в {@code RawBlock}, писатель выводит их обратно на прежнее место.</p>
 *
 * @param afterSection название известной секции, после которой стоял фрагмент
 *                     (пустая строка — сразу после заголовка плана)
 * @param lines        строки фрагмента как есть, без символов перевода строки
 */
public record RawBlock(String afterSection, List<String> lines) {

    /** Делает список строк неизменяемым. */
    public RawBlock {
        afterSection = afterSection == null ? "" : afterSection;
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }
}
