package ru.cashprediction.core.ui.dump;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.HotkeyTable;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.text.Texts;

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
            specSection = Objects.requireNonNull(specSection, "specSection");
            description = Objects.requireNonNull(description, "description");
            if (number < 1 || number > 15 || number == 12
                    || !specSection.equals("§10 №" + number) || description.isBlank()) {
                throw new IllegalArgumentException("specification reference");
            }
            if (clients.isEmpty() || !Set.of("fx", "swing", "web").containsAll(clients)) {
                throw new IllegalArgumentException("clients");
            }
            validatePattern(pointer);
        }
    }

    private final List<Entry> entries;
    /** Записи, реально поглотившие расхождение в текущем прогоне. */
    private final Set<Entry> used = new LinkedHashSet<>();

    /**
     * Создаёт список.
     *
     * @param entries записи
     */
    public AllowedDiffs(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        Set<String> rules = new LinkedHashSet<>();
        for (Entry entry : this.entries) {
            for (String client : entry.clients()) {
                if (!rules.add(entry.pointer() + "\n" + client)) {
                    throw new IllegalArgumentException("duplicate rule");
                }
            }
        }
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
        Object root = JsonParser.parse(json);
        List<Object> rawEntries;
        if (root instanceof List<?> list) {
            rawEntries = List.copyOf(list);
        } else {
            rawEntries = Json.list(Json.asObject(root, "allowed-diffs"), "entries");
        }
        List<Entry> result = new java.util.ArrayList<>();
        for (Object raw : rawEntries) {
            Map<String, Object> item = Json.asObject(raw, "allowed-diff");
            Set<String> clients = new LinkedHashSet<>();
            for (Object client : Json.list(item, "clients")) {
                if (!(client instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("clients");
                }
                if (!clients.add(text)) {
                    throw new IllegalArgumentException("duplicate client");
                }
            }
            if (clients.isEmpty()) {
                throw new IllegalArgumentException("clients");
            }
            result.add(new Entry(Math.toIntExact(Json.requireLong(item, "number")), Json.requireString(item, "pointer"),
                    clients, Json.requireString(item, "specSection"), Json.requireString(item, "description")));
        }
        return new AllowedDiffs(result);
    }

    /**
     * Отбрасывает допустимые расхождения и отмечает сработавшие записи.
     *
     * @param client      клиент
     * @param differences расхождения
     * @return недопустимые расхождения
     */
    public List<DumpDiff.Difference> filter(String client, List<DumpDiff.Difference> differences) {
        return filter(client, differences, null, null);
    }

    /**
     * Фильтрует расхождения с контекстом деревьев сравнения. Перенос тулбара разрешён только
     * при ширине содержимого меньше 1200 в обоих дампах; без контекста правило №15 не применяется.
     *
     * @param client клиент, для которого проверяется исключение
     * @param differences различия DumpDiff
     * @param expectedTree ожидаемое дерево DumpNormalizer.comparisonTree
     * @param actualTree фактическое дерево DumpNormalizer.comparisonTree
     * @return неразрешённые различия
     */
    public List<DumpDiff.Difference> filter(String client, List<DumpDiff.Difference> differences,
                                           Object expectedTree, Object actualTree) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(differences, "differences");
        if (!Set.of("fx", "swing", "web").contains(client)) {
            throw new IllegalArgumentException("client");
        }
        List<DumpDiff.Difference> rejected = new java.util.ArrayList<>();
        for (DumpDiff.Difference difference : differences) {
            Entry accepted = entries.stream().filter(entry -> entry.clients().contains(client)
                    && matches(entry.pointer(), difference.pointer()) && permittedValue(entry, difference)
                    && (entry.number() != 15 || narrowFrame(expectedTree) && narrowFrame(actualTree))).findFirst().orElse(null);
            if (accepted == null) {
                rejected.add(difference);
            } else {
                used.add(accepted);
            }
        }
        return List.copyOf(rejected);
    }

    /** @return записи, не сработавшие ни разу (ошибка сборки в конце прогона) */
    public List<Entry> unused() {
        return entries.stream().filter(entry -> !used.contains(entry)).toList();
    }

    /** Ограничивает исключения значением там, где спецификация разрешает только конкретную замену. */
    private static boolean permittedValue(Entry entry, DumpDiff.Difference difference) {
        Object left = difference.expected();
        Object right = difference.actual();
        if (Objects.equals(left, right)) return false;
        if (entry.number() == 7 && difference.pointer().equals("/alerts/crashRecovery/header")) {
            if (!(left instanceof String a) || !(right instanceof String b)) return false;
            return recoveryHeader(a).equals(recoveryHeader(b))
                    && !recoveryHeader(a).equals(a) && !recoveryHeader(b).equals(b);
        }
        if (Set.of(1, 7, 9, 10, 14).contains(entry.number())
                && !difference.pointer().endsWith("/content")) {
            if ((left == null) == (right == null)) return false;
            Object present = left == null ? right : left;
            if (!(present instanceof Map<?, ?> map)) return false;
            if (entry.number() == 1) {
                return Objects.equals(map.get("kind"), "Radio") && Objects.equals(map.get("group"), "store")
                        && (!difference.pointer().endsWith(".server") || Boolean.FALSE.equals(map.get("enabled")));
            }
            if (entry.number() == 14) return Set.of("offline", "stopped", "crashed").contains(map.get("kind"));
            return map.keySet().equals(Set.of("id", "text", "tooltip", "enabled", "isDefault", "x"));
        }
        if (entry.number() == 2) {
            String[] segments = difference.pointer().split("/");
            String id = segments[segments.length - 2].replace("~1", "/").replace("~0", "~");
            try {
                CommandId command = CommandId.byId(id).orElseThrow(() -> new IllegalArgumentException("command"));
                String desktop = HotkeyTable.shownAccelerator(command, ClientKind.FX).map(c -> c.display()).orElse("");
                String web = HotkeyTable.shownAccelerator(command, ClientKind.WEB).map(c -> c.display()).orElse("");
                return !desktop.equals(web) && pair(left, right, desktop, web);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        if (entry.number() == 3) return pair(left, right, UiText.get("menu.file.exit.tip"), UiText.get("menu.file.exit.tip.web"));
        if (entry.number() == 4) return pair(left, right, UiText.get("alert.halt.header"), UiText.get("alert.halt.web"));
        if (entry.number() == 6) {
            // В шаблоне строка клиента содержит также версию Java; остальное содержимое должно совпасть дословно.
            String template = UiText.get("alert.about.content", "<version>", "<client>", "<java>", "<home>");
            String[] lines = template.split("\n", -1);
            int clientLine = -1;
            for (int index = 0; index < lines.length; index++) if (lines[index].contains("<client>")) clientLine = index;
            if (!(left instanceof String a) || !(right instanceof String b) || clientLine < 0) return false;
            List<String> aLines = new java.util.ArrayList<>(List.of(a.split("\n", -1)));
            List<String> bLines = new java.util.ArrayList<>(List.of(b.split("\n", -1)));
            if (aLines.size() != lines.length || bLines.size() != lines.length) return false;
            String line = lines[clientLine];
            String prefix = line.substring(0, line.indexOf("<client>"));
            String suffix = line.substring(line.indexOf("<client>") + "<client>".length());
            // Часть Java не разрешена исключением: вырезается только значение клиента до следующего фрагмента.
            String fixed = suffix.contains("<java>") ? suffix.substring(0, suffix.indexOf("<java>")) : suffix;
            String aLine = aLines.get(clientLine), bLine = bLines.get(clientLine);
            int aEnd = fixed.isEmpty() ? aLine.length() : aLine.indexOf(fixed, prefix.length());
            int bEnd = fixed.isEmpty() ? bLine.length() : bLine.indexOf(fixed, prefix.length());
            if (!aLine.startsWith(prefix) || !bLine.startsWith(prefix) || aEnd < 0 || bEnd < 0) return false;
            if (!clientTitle(aLine.substring(prefix.length(), aEnd))
                    || !clientTitle(bLine.substring(prefix.length(), bEnd))) return false;
            aLines.set(clientLine, prefix + aLine.substring(aEnd));
            bLines.set(clientLine, prefix + bLine.substring(bEnd));
            return aLines.equals(bLines);
        }
        if (entry.number() == 8 && !entry.clients().contains("web")) {
            return left instanceof String a && right instanceof String b
                    && desktopSnapshotLocations(a).equals(desktopSnapshotLocations(b));
        }
        if (entry.number() == 11 && difference.pointer().equals("/frame/titleBar")) return pair(left, right, "os", "tab");
        if (entry.number() == 11) return left == null && minimumSize(right) || right == null && minimumSize(left);
        if (entry.number() == 13) {
            // При попарном сравнении FX может находиться с любой стороны эталона.
            Object present = left == null ? right : left;
            return (left == null) != (right == null) && present instanceof Map<?, ?> map
                    && Objects.equals(map.get("purpose"), "replaceFile");
        }
        if (entry.number() == 15) return pair(left, right, false, true);
        return true;
    }

    /** Убирает только известные адреса хранилищ; строки JSON/XML и прочие имена файлов остаются точными. */
    private static String desktopSnapshotLocations(String text) {
        String result = text;
        String registryTitle = Texts.get("session.store.title.registry");
        String registryCanonical = UiText.get("s2.recovery.registryBlock", registryTitle,
                "<node>\\<client>", "").split("\n", -1)[0];
        for (String separator : List.of("/", "\\")) {
            for (String client : List.of("fx", "swing")) {
                String header = UiText.get("s2.recovery.registryBlock", registryTitle,
                        "<node>" + separator + client, "").split("\n", -1)[0];
                result = result.replaceAll("(?m)^" + java.util.regex.Pattern.quote(header) + "$",
                        java.util.regex.Matcher.quoteReplacement(registryCanonical));
            }
        }
        String title = Texts.get("session.store.title.xml");
        String canonical = UiText.get("s2.recovery.fileBlock", title,
                "<CashMemory>/session-<client>.xml", "").split("\n", -1)[0];
        for (String separator : List.of("/", "\\")) {
            for (String client : List.of("fx", "swing")) {
                String header = UiText.get("s2.recovery.fileBlock", title,
                        "<CashMemory>" + separator + "session-" + client + ".xml", "").split("\n", -1)[0];
                result = result.replaceAll("(?m)^" + java.util.regex.Pattern.quote(header) + "$",
                        java.util.regex.Matcher.quoteReplacement(canonical));
            }
        }
        return result;
    }

    /** Проверяет обе ориентации попарного сравнения. */
    private static boolean pair(Object left, Object right, Object first, Object second) {
        return Objects.equals(left, first) && Objects.equals(right, second)
                || Objects.equals(left, second) && Objects.equals(right, first);
    }

    /** Отсутствие ограничения браузера не разрешает произвольный минимальный размер клиента. */
    private static boolean minimumSize(Object value) {
        return value instanceof Map<?, ?> map && map.size() == 2
                && map.get("width") instanceof Number width && map.get("height") instanceof Number height
                && new java.math.BigDecimal(width.toString()).compareTo(java.math.BigDecimal.valueOf(900)) == 0
                && new java.math.BigDecimal(height.toString()).compareTo(java.math.BigDecimal.valueOf(600)) == 0;
    }

    /** Название клиента соответствует каталогу; произвольный текст не скрывается под строкой клиента. */
    private static boolean clientTitle(String value) {
        String fx = UiText.get("client.fx", "").strip();
        return value.equals(UiText.get("client.swing")) || value.equals(UiText.get("client.web"))
                || value.equals(fx) || value.startsWith(fx + " ") && value.substring(fx.length() + 1).matches("[0-9][0-9A-Za-z.+-]*");
    }

    /** Убирает только известное имя клиента в конце строки маркера, сохраняя дату и весь остальной заголовок. */
    private static String recoveryHeader(String header) {
        String marker = UiText.get("s2.startup.marker", "<date>", "<client>");
        String prefix = marker.substring(0, marker.indexOf("<date>"));
        String between = marker.substring(marker.indexOf("<date>") + 6, marker.indexOf("<client>"));
        String suffix = marker.substring(marker.indexOf("<client>") + 8);
        int start = header.lastIndexOf('\n') + 1;
        String line = header.substring(start);
        if (!line.startsWith(prefix)) return header;
        for (String client : List.of(UiText.get("s2.startup.clientFx"), UiText.get("client.swing"), UiText.get("client.web"))) {
            String ending = between + client + suffix;
            if (line.endsWith(ending)) return header.substring(0, header.length() - ending.length()) + between + "<client>" + suffix;
        }
        return header;
    }

    /** Контекст геометрии берётся из дампа, а не из предположения о размере запуска. */
    private static boolean narrowFrame(Object tree) {
        return tree instanceof Map<?, ?> map && map.get("frame") instanceof Map<?, ?> frame
                && frame.get("contentWidth") instanceof Number width
                && new java.math.BigDecimal(width.toString()).signum() > 0
                && new java.math.BigDecimal(width.toString()).compareTo(java.math.BigDecimal.valueOf(1200)) < 0;
    }

    /** Проверяет RFC 6901 и запрещает неоднозначные либо чрезмерно широкие шаблоны. */
    private static void validatePattern(String pointer) {
        if (!pointer.startsWith("/") || pointer.equals("/**") || pointer.equals("/*")) {
            throw new IllegalArgumentException("pointer");
        }
        String[] segments = pointer.substring(1).split("/", -1);
        if (pointer.equals("/alerts") || pointer.equals("/menuBar") || pointer.equals("/windows")
                || pointer.contains("/$order")
                || pointer.matches("/(alerts|menuBar|windows)/\\*/\\*\\*")) {
            throw new IllegalArgumentException("broad pointer");
        }
        if (segments.length < 3 && segments[segments.length - 1].equals("**")) {
            throw new IllegalArgumentException("broad pointer");
        }
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || segment.equals("**") && index != segments.length - 1
                    || segment.contains("*") && !segment.equals("*") && !segment.equals("**")) {
                throw new IllegalArgumentException("pointer wildcard");
            }
            for (int offset = 0; offset < segment.length(); offset++) {
                if (segment.charAt(offset) == '~') {
                    if (++offset >= segment.length() || segment.charAt(offset) != '0' && segment.charAt(offset) != '1') {
                        throw new IllegalArgumentException("pointer escape");
                    }
                }
            }
        }
    }

    /** Сверяет шаблон JSON Pointer: * - один сегмент, ** - все оставшиеся сегменты. */
    private static boolean matches(String pattern, String pointer) {
        if (pattern == null || pointer == null || !pattern.startsWith("/") || !pointer.startsWith("/")) {
            return false;
        }
        String[] expected = pattern.substring(1).split("/", -1);
        String[] actual = pointer.substring(1).split("/", -1);
        int left = 0;
        int right = 0;
        while (left < expected.length && right < actual.length) {
            if (expected[left].equals("**")) {
                return left == expected.length - 1;
            }
            if (!expected[left].equals("*") && !expected[left].equals(actual[right])) {
                return false;
            }
            left++;
            right++;
        }
        return left == expected.length && right == actual.length
                || left == expected.length - 1 && expected[left].equals("**");
    }
}
