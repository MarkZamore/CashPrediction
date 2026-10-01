package ru.cashprediction.core.ui.selftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.text.UiText;

/** Строгий разбор языка сценариев с сохранением физических номеров строк. */
final class ScriptParser {
    private ScriptParser() { }

    static SelfTestScript parse(String name, String source) {
        Objects.requireNonNull(source, "text");
        List<SelfTestScript.Line> lines = new ArrayList<>();
        String[] raw = (source.startsWith("\uFEFF") ? source.substring(1) : source).split("\\R", -1);
        for (int i = 0; i < raw.length; i++) {
            try {
                List<String> tokens = tokens(raw[i]);
                if (!tokens.isEmpty()) lines.add(new SelfTestScript.Line(i + 1, withoutComment(raw[i]), command(tokens)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(UiText.get("s2.selftest.parse", name, i + 1, raw[i]), e);
            }
        }
        return new SelfTestScript(name, lines);
    }

    /** Оставляет исходные кавычки в журнале, удаляя только комментарий за пределами значения. */
    private static String withoutComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '"') i++;
            else if (c == '"') quoted = !quoted;
            else if (!quoted && c == '#') return line.substring(0, i).strip();
        }
        return line.strip();
    }

    static SelfTestScript load(String value) {
        Objects.requireNonNull(value, "nameOrPath");
        try {
            if (SelfTestScript.SCENARIOS.contains(value)) {
                var stream = SelfTestScript.class.getResourceAsStream(SelfTestScript.RESOURCE_DIR + value + ".cps");
                if (stream == null) throw new IOException(value);
                try (var input = stream) {
                    return parse(value, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            Path path = Path.of(value);
            String name = path.getFileName().toString().replaceFirst("\\.cps$", "");
            return parse(name, Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException(UiText.get("s2.selftest.load", value), e);
        }
    }

    /** Сохраняет пустые значения, обратные слеши путей и решётки внутри кавычек. */
    static List<String> tokens(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false, present = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                word.append('"'); i++;
            } else if (c == '"') { quoted = !quoted; present = true;
            } else if (!quoted && c == '#') { break;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (present) { result.add(word.toString()); word.setLength(0); present = false; }
            } else { word.append(c); present = true; }
        }
        if (quoted) throw new IllegalArgumentException("quote");
        if (present) result.add(word.toString());
        return result;
    }

    private static SelfTestCommand command(List<String> t) {
        String op = t.getFirst();
        int count = switch (op) {
            case "sample", "save", "snapshot", "crash", "throw", "exit" -> 1;
            case "size", "quickedit", "slider", "spinner", "enter", "button" -> 3;
            case "field" -> 4;
            case "fill", "open", "dblclick", "rowclick", "pick" -> -1;
            default -> 2;
        };
        if (count > 0 && t.size() != count) throw new IllegalArgumentException("arity");
        return switch (op) {
            case "wait" -> new SelfTestCommand.Wait(nonnegative(t.get(1)));
            case "sample" -> new SelfTestCommand.Sample();
            case "save" -> new SelfTestCommand.Save();
            case "snapshot" -> new SelfTestCommand.Snapshot();
            case "crash" -> new SelfTestCommand.Crash();
            case "throw" -> new SelfTestCommand.Throw();
            case "exit" -> new SelfTestCommand.Exit();
            case "open" -> { range(t, 2, Integer.MAX_VALUE); yield new SelfTestCommand.Open(WindowType.valueOf(t.get(1)), pairs(t, 2)); }
            case "fill" -> { range(t, 3, Integer.MAX_VALUE); yield new SelfTestCommand.Fill(t.get(1), pairs(t, 2)); }
            case "ok" -> new SelfTestCommand.Ok(t.get(1));
            case "cancel" -> new SelfTestCommand.Cancel(t.get(1));
            case "view" -> new SelfTestCommand.View(ViewMode.valueOf(t.get(1)));
            case "period" -> new SelfTestCommand.Period(PeriodChoice.valueOf(t.get(1)));
            case "filter" -> {
                Map<String, String> pair = pairs(t, 1);
                var entry = pair.entrySet().iterator().next();
                if (!List.of("true", "false").contains(entry.getValue())) throw new IllegalArgumentException("boolean");
                yield new SelfTestCommand.Filter(entry.getKey(), Boolean.parseBoolean(entry.getValue()));
            }
            case "select" -> new SelfTestCommand.Select(t.get(1));
            case "quickedit" -> new SelfTestCommand.QuickEdit(t.get(1), t.get(2));
            case "menus" -> new SelfTestCommand.Menus(t.get(1));
            case "signal" -> new SelfTestCommand.Signal(t.get(1));
            case "today" -> new SelfTestCommand.Today(LocalDate.parse(t.get(1)));
            case "size" -> { int w = nonnegative(t.get(1)), h = nonnegative(t.get(2)); if (w == 0 || h == 0) throw new IllegalArgumentException("size"); yield new SelfTestCommand.Size(w, h); }
            case "menu" -> new SelfTestCommand.Menu(t.get(1));
            case "click" -> new SelfTestCommand.Click(t.get(1));
            case "key" -> new SelfTestCommand.Key(KeyChord.parse(t.get(1)));
            case "dblclick" -> { range(t, 2, 3); yield new SelfTestCommand.DoubleClick(t.get(1), t.size() == 3 ? t.get(2) : ""); }
            case "rowclick" -> { range(t, 2, 3); yield new SelfTestCommand.RowClick(t.get(1), t.size() == 3 ? t.get(2) : ""); }
            case "context" -> new SelfTestCommand.Context(t.get(1));
            case "hover" -> new SelfTestCommand.Hover(t.get(1));
            case "field" -> new SelfTestCommand.Field(t.get(1), t.get(2), t.get(3));
            case "button" -> new SelfTestCommand.Button(t.get(1), t.get(2));
            case "answer" -> new SelfTestCommand.Answer(t.get(1));
            case "chooser" -> new SelfTestCommand.Chooser(t.get(1).equals("cancel") ? null : Path.of(t.get(1)));
            case "dump" -> new SelfTestCommand.Dump(t.get(1).endsWith(".json")
                    ? Path.of(t.get(1)).getFileName().toString().replaceFirst("\\.json$", "") : t.get(1));
            case "shot" -> new SelfTestCommand.Shot(t.get(1));
            case "slider" -> new SelfTestCommand.SliderSet(t.get(1), Integer.parseInt(t.get(2)));
            case "spinner" -> new SelfTestCommand.SpinnerSet(t.get(1), Long.parseLong(t.get(2)));
            case "filtertype" -> new SelfTestCommand.FilterType(t.get(1));
            case "pick" -> { range(t, 4, 5); if (t.size() == 5 && !t.get(4).equals("activate")) throw new IllegalArgumentException("activate"); yield new SelfTestCommand.ListPick(t.get(1), t.get(2), t.get(3), t.size() == 5); }
            case "enter" -> new SelfTestCommand.FieldEnter(t.get(1), t.get(2));
            default -> throw new IllegalArgumentException("command");
        };
    }

    private static int nonnegative(String text) {
        int value = Integer.parseInt(text);
        if (value < 0) throw new IllegalArgumentException("negative");
        return value;
    }

    private static void range(List<String> t, int min, int max) {
        if (t.size() < min || t.size() > max) throw new IllegalArgumentException("arity");
    }

    private static Map<String, String> pairs(List<String> t, int from) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = from; i < t.size(); i++) {
            int eq = t.get(i).indexOf('=');
            if (eq < 1) throw new IllegalArgumentException("pair");
            if (values.putIfAbsent(t.get(i).substring(0, eq), t.get(i).substring(eq + 1)) != null)
                throw new IllegalArgumentException("duplicate");
        }
        return values;
    }
}
