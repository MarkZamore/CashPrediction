package ru.cashprediction.core.ui.selftest;

import java.util.List;
import java.util.Objects;

/**
 * Разобранный сценарий самотеста (архитектура §6.2). Сценарии лежат в ресурсах ядра
 * {@code ru/cashprediction/core/ui/scenarios/<имя>.cps} (UTF-8), чтобы и собранные exe могли их выполнить.
 *
 * <p><b>Синтаксис</b> (разбор перенесён из Swing {@code SelfTestTokenizer}): одна команда на строку; {@code #} до конца
 * строки — комментарий; пустые строки пропускаются; слова разделяются пробелами; значение с пробелами — в двойных
 * кавычках, {@code \"} внутри кавычек — кавычка; {@code ключ=значение} — одно слово. Неизвестная команда — ошибка
 * разбора с номером строки.</p>
 *
 * @param name  имя сценария ({@code s02-sample-table})
 * @param lines команды по порядку
 */
public record SelfTestScript(String name, List<Line> lines) {

    /** Папка ресурсов сценариев. */
    public static final String RESOURCE_DIR = "/ru/cashprediction/core/ui/scenarios/";

    /** Сценарии паритета (архитектура §6.2). */
    public static final List<String> SCENARIOS = List.of("s01-first-run", "s02-sample-table", "s03-chart",
            "s04-context-menus", "s05-forms-plan", "s06-forms-ops", "s07-forms-misc", "s08-alerts", "s09-whatif",
            "s10-filter-empty-states", "s11-past-group-reveal", "s12-quick-edit", "s13-undo-redo",
            "s14-save-conflicts", "s15-keyboard", "s16-exit-dirty", "s17-recovery-dialog", "s18-already-running");

    /**
     * Строка сценария.
     *
     * @param number  номер строки файла (с 1)
     * @param text    исходный текст строки без комментария
     * @param command разобранная команда
     */
    public record Line(int number, String text, SelfTestCommand command) {
        /** Проверяет поля. */
        public Line {
            text = Objects.requireNonNullElse(text, "");
            Objects.requireNonNull(command, "command");
        }
    }

    /** Проверяет поля и копирует список. */
    public SelfTestScript {
        Objects.requireNonNull(name, "name");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    /**
     * Разбирает текст сценария.
     *
     * @param name имя сценария
     * @param text текст файла
     * @return сценарий
     * @throws IllegalArgumentException при ошибке разбора (с номером строки)
     */
    public static SelfTestScript parse(String name, String text) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — SelfTestScript.parse");
    }

    /**
     * Загружает сценарий по имени из ресурсов ядра или по пути к файлу.
     *
     * @param nameOrPath имя из {@link #SCENARIOS} или путь к файлу {@code .cps}
     * @return сценарий
     * @throws IllegalArgumentException если сценария нет или он не разбирается
     */
    public static SelfTestScript load(String nameOrPath) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — SelfTestScript.load");
    }
}
