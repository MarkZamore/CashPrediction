package ru.cashprediction.core.ui.dump;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Закрытый список допустимых различий (спецификация v2, §10; архитектура §6.1), файл
 * {@code core/src/test/resources/ui-golden/allowed-diffs.json}. Каждая запись ссылается на раздел спецификации;
 * запись, которая ни разу не сработала за прогон, — ошибка сборки.
 *
 * <p>Не потокобезопасен: учёт сработавших записей ведётся в экземпляре.</p>
 */
public final class AllowedDiffs {

    /** Путь файла в модуле ядра. */
    public static final String RESOURCE = "ui-golden/allowed-diffs.json";

    /**
     * Запись списка.
     *
     * @param number      номер строки таблицы §10
     * @param pointer     шаблон JSON Pointer ({@code *} — один сегмент, {@code **} — любой хвост)
     * @param clients     клиенты, для которых различие допустимо
     * @param specSection раздел спецификации, например {@code §10 №2}
     * @param description описание различия
     */
    public record Entry(int number, String pointer, Set<String> clients, String specSection, String description) {
        /** Проверяет поля и копирует множество. */
        public Entry {
            Objects.requireNonNull(pointer, "pointer");
            clients = Set.copyOf(Objects.requireNonNull(clients, "clients"));
            specSection = Objects.requireNonNullElse(specSection, "");
            description = Objects.requireNonNullElse(description, "");
        }
    }

    private final List<Entry> entries;

    /**
     * Создаёт список.
     *
     * @param entries записи
     */
    public AllowedDiffs(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** @return записи */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Читает список из JSON.
     *
     * @param json текст файла
     * @return список
     */
    public static AllowedDiffs parse(String json) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — AllowedDiffs.parse");
    }

    /**
     * Отбрасывает допустимые расхождения и отмечает сработавшие записи.
     *
     * @param client      клиент
     * @param differences расхождения
     * @return недопустимые расхождения
     */
    public List<DumpDiff.Difference> filter(String client, List<DumpDiff.Difference> differences) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — AllowedDiffs.filter");
    }

    /** @return записи, не сработавшие ни разу (ошибка сборки в конце прогона) */
    public List<Entry> unused() {
        throw new UnsupportedOperationException("S2: core-protocol-dump — AllowedDiffs.unused");
    }
}
