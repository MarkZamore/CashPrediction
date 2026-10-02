package ru.cashprediction.parity.check.visual;

import java.util.*;
import ru.cashprediction.core.ui.selftest.*;
import ru.cashprediction.parity.pipeline.ParityPipeline;

/** Выбирает ограниченные контрольные точки настоящих сценариев и дополняет их снимком. */
public final class VisualPlan {
    /** Четыре сценария, указанные архитектурой §6.3. */
    public static final List<String> SCENARIOS = List.of("s02-sample-table", "s03-chart", "s05-forms-plan", "s08-alerts");
    private VisualPlan() { }

    /** Отдельная контрольная точка; префикс исходного сценария не заменяется командами модели. */
    public record Checkpoint(String scenario, String step, SelfTestScript script) { }

    /** Проверяет двойной opt-in; запрос visual без realClients является ошибкой, а не пропуском. */
    public static boolean enabled(Properties properties) {
        if (!Boolean.parseBoolean(properties.getProperty("parity.visual", "false"))) return false;
        if (!Boolean.parseBoolean(properties.getProperty("parity.realClients", "false")))
            throw new IllegalArgumentException("parity.visual=true requires parity.realClients=true");
        return true;
    }

    /** Пустой фильтр сохраняет четыре пробы; явные префиксы и all доступны для всех 18 сценариев ядра. */
    public static List<Checkpoint> checkpoints(String scenarios, String selection) {
        var chosen = scenarios == null || scenarios.isBlank() ? SCENARIOS
                : scenarios.trim().equals("all") ? SelfTestScript.SCENARIOS
                : ParityPipeline.scenarios(SelfTestScript.SCENARIOS, scenarios);
        if (!Set.of("first", "all").contains(selection)) throw new IllegalArgumentException("parity.visual.checkpoints: " + selection);
        var result = new ArrayList<Checkpoint>();
        for (String scenario : chosen) {
            SelfTestScript source = SelfTestScript.load(scenario);
            for (var line : source.lines()) if (line.command() instanceof SelfTestCommand.Dump dump) {
                result.add(extend(source, dump.step()));
                if (selection.equals("first")) break;
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Empty visual matrix");
        return List.copyOf(result);
    }

    /** Сохраняет исходные команды до нужного dump и добавляет только размер и shot. */
    public static Checkpoint extend(SelfTestScript source, String step) {
        StringBuilder text = new StringBuilder("size 1200 800\n");
        boolean found = false;
        for (var line : source.lines()) {
            text.append(line.text()).append('\n');
            if (line.command() instanceof SelfTestCommand.Dump dump && dump.step().equals(step)) {
                text.append("shot ").append(step).append('\n'); found = true; break;
            }
        }
        if (!found) throw new IllegalArgumentException("Unknown checkpoint: " + source.name() + "/" + step);
        return new Checkpoint(source.name(), step, SelfTestScript.parse("visual-" + source.name() + "-" + step, text.toString()));
    }

    /** Возвращает текст разобранного сценария для внешнего пути --selftest. */
    public static String text(SelfTestScript script) {
        return String.join("\n", script.lines().stream().map(SelfTestScript.Line::text).toList()) + "\n";
    }
}
