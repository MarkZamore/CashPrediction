package ru.cashprediction.parity.check.hotkey;

import java.util.ArrayList;
import java.util.List;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.selftest.SelfTestScript;

/** Полная матрица привязок §7: подготовка выполняется через настоящий сценарий, а не подмену состояния. */
public final class HotkeyCases {
    private HotkeyCases() { }

    /** Выбирает только незакрытые опыты, не повторяя подтверждённую обычную матрицу и перепись классов. */
    public static List<Probe> residual(List<Probe> probes, String client) {
        return probes.stream().filter(probe -> client.equals("web") ? probe.name().equals("past.toggle Space")
                : probe.name().endsWith(" en") || probe.name().endsWith(" ru") || probe.chord().key().equals("ALT")).toList();
    }

    /** Один независимый опыт: команда, подготовка, ожидаемая подсказка и вариант русской раскладки DOM. */
    public record Probe(String name, String command, String setup, KeyChord chord, String hint, boolean russian) {
        /** Строит принимаемый SelfTestRunner сценарий; после измерения нет действий, меняющих счётчики. */
        public String script() {
            return "key Esc\nsample\n" + setup
                    + "dump before\nkey " + chord.display() + "\ndump after\n";
        }
    }

    /** Ограничивает диагностический повтор точными именами; опечатка не превращает запуск в пустой успех. */
    public static List<Probe> select(List<Probe> probes, String names) {
        if (names.isBlank()) return probes;
        var selected = new java.util.LinkedHashSet<String>();
        for (String name : names.split(",", -1))
            if (name.isBlank() || !selected.add(name.strip())) throw new IllegalArgumentException("Invalid parity.hotkeys: " + names);
        var result = probes.stream().filter(probe -> selected.contains(probe.name())).toList();
        for (String name : selected)
            if (result.stream().noneMatch(probe -> probe.name().equals(name)))
                throw new IllegalArgumentException("Unknown parity.hotkeys probe: " + name);
        return result;
    }

    /** Возвращает все привязки, запреты браузера и отдельные проверки отключённых команд. */
    public static List<Probe> forClient(String client) {
        ClientKind kind = switch (client) {
            case "fx" -> ClientKind.FX;
            case "swing" -> ClientKind.SWING;
            case "web" -> ClientKind.WEB;
            default -> throw new IllegalArgumentException(client);
        };
        var result = new ArrayList<Probe>();
        for (HotkeyBinding binding : HotkeyTable.bindings(kind)) {
            // Браузер резервирует Ctrl+N/O/T; проверяются принимаемые страницей Alt+Shift варианты.
            if (kind == ClientKind.WEB && reserved(binding.chord())) continue;
            String setup = binding.command() == CommandId.PAST_TOGGLE && kind == ClientKind.WEB
                    ? "rowclick past@group date\n" : setup(binding.command());
            result.add(new Probe(binding.command().id() + " " + binding.chord().display(),
                    binding.command().id(), setup, binding.chord(), "", false));
            if (kind == ClientKind.WEB && binding.chord().key().matches("[A-Z]"))
                result.add(new Probe(binding.command().id() + " " + binding.chord().display() + " ru",
                        binding.command().id(), setup, binding.chord(), "", true));
            if (kind != ClientKind.WEB && binding.chord().key().matches("[A-Z]")) {
                result.add(new Probe(binding.command().id() + " " + binding.chord().display() + " en",
                        binding.command().id(), setup, binding.chord(), "", false));
                result.add(new Probe(binding.command().id() + " " + binding.chord().display() + " ru",
                        binding.command().id(), setup, binding.chord(), "", true));
            }
        }
        for (HotkeyBinding binding : HotkeyTable.bindings(kind)) {
            String hint = switch (binding.command()) {
                case EDIT_UNDO -> "status.hint.nothingToUndo";
                case EDIT_REDO -> "status.hint.nothingToRedo";
                case EDIT_ADJUST -> "status.hint.noRuleEvent";
                case EDIT_DELETE -> "status.hint.noOperation";
                case EDIT_EDIT -> "status.hint.noOperation";
                default -> "";
            };
            if (!hint.isEmpty()) result.add(new Probe("disabled " + binding.command().id() + " "
                    + binding.chord().display(), binding.command().id(), "select total@2026-10\n",
                    binding.chord(), hint, false));
            if (kind == ClientKind.WEB && !hint.isEmpty() && binding.chord().key().matches("[A-Z]"))
                result.add(new Probe("disabled " + binding.command().id() + " " + binding.chord().display() + " ru",
                        binding.command().id(), "select total@2026-10\n", binding.chord(), hint, true));
            if (kind != ClientKind.WEB && !hint.isEmpty() && binding.chord().key().matches("[A-Z]"))
                for (boolean russian : List.of(false, true)) result.add(new Probe("disabled " + binding.command().id()
                        + " " + binding.chord().display() + (russian ? " ru" : " en"), binding.command().id(),
                        "select total@2026-10\n", binding.chord(), hint, russian));
        }
        // Разбор до запуска обнаруживает непринимаемый синтаксис, не маскируя его успешным счётчиком.
        result.forEach(probe -> SelfTestScript.parse("hotkey", probe.script()));
        return List.copyOf(result);
    }

    private static boolean reserved(KeyChord chord) {
        return chord.ctrl() && !chord.alt() && !chord.shift() && List.of("N", "O", "T").contains(chord.key());
    }

    private static String setup(CommandId command) {
        return switch (command) {
            case EDIT_UNDO -> "select r1@2026-10-05\nmenu edit.skip\nselect total@2026-10\n";
            case EDIT_REDO -> "select r1@2026-10-05\nmenu edit.skip\nmenu edit.undo\nselect r1@2026-10-05\n";
            case FILTER_CLEAR, FILTER_FOCUS_TABLE -> "filtertype probe\n";
            case PAST_TOGGLE -> "select past@group\n";
            default -> "select r1@2026-10-05\n";
        };
    }
}
