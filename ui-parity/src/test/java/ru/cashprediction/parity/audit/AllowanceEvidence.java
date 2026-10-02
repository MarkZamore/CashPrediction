package ru.cashprediction.parity.audit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.parity.pipeline.DumpTrees;
import ru.cashprediction.parity.pipeline.ParityPipeline;

/** Читает SHA-закреплённые widget references и ограниченные DOM-наблюдения без создания модельных дампов. */
public final class AllowanceEvidence {
    private static final Set<String> SCOPES = Set.of("halt-header", "snapshot", "replace-file",
            "uncaught-buttons", "narrow-toolbar", "offline", "stopped", "crashed");
    private AllowanceEvidence() { }

    /** Загружает все пары атомарно: ошибочный файл не оставляет частично поглощённых allowances. */
    public static List<ParityPipeline.EvidencePair> load(Path manifest) throws Exception {
        Map<String, Object> root = Json.asObject(JsonParser.parse(Files.readString(manifest)), "evidence");
        if (!root.keySet().equals(Set.of("pairs"))) throw new IllegalArgumentException("Evidence manifest fields");
        var result = new ArrayList<ParityPipeline.EvidencePair>();
        var labels = new java.util.HashSet<String>();
        for (Object raw : Json.list(root, "pairs")) {
            Map<String, Object> pair = Json.asObject(raw, "pair");
            if (!pair.keySet().equals(Set.of("label", "scope", "reference", "actual")))
                throw new IllegalArgumentException("Evidence pair fields");
            String label = Json.requireString(pair, "label"), scope = Json.requireString(pair, "scope");
            if (!label.matches("[a-zA-Z0-9_-]+") || !labels.add(label) || !SCOPES.contains(scope))
                throw new IllegalArgumentException("Evidence label/scope");
            Source reference = source(Json.asObject(pair.get("reference"), "reference"));
            Source actual = source(Json.asObject(pair.get("actual"), "actual"));
            UiDump expected = DumpTrees.read(reference.text());
            if (!Set.of("fx", "swing").contains(expected.client()))
                throw new IllegalArgumentException("Native widget reference required");
            Object left = project(expected, reference, scope);
            Object right;
            String client;
            if (Set.of("offline", "stopped", "crashed").contains(scope)) {
                if (!expected.screens().isEmpty()) throw new IllegalArgumentException("Reference screens must be absent");
                right = screen(Json.asObject(JsonParser.parse(actual.text()), "screen"), scope);
                client = "web";
            } else {
                UiDump observed = DumpTrees.read(actual.text());
                client = observed.client();
                if (!Set.of("fx", "swing", "web").contains(client) || client.equals(expected.client()))
                    throw new IllegalArgumentException("Distinct real clients required");
                if (scope.equals("narrow-toolbar")) {
                    if (expected.frame() == null || observed.frame() == null
                            || expected.frame().contentWidth() != observed.frame().contentWidth())
                        throw new IllegalArgumentException("Measured reference/actual widths differ");
                }
                right = project(observed, actual, scope);
            }
            result.add(new ParityPipeline.EvidencePair(label, client, expected.client(), left, right,
                    "scope=" + scope + "; reference=" + reference.path() + " SHA256=" + reference.sha()
                            + "; actual=" + actual.path() + " SHA256=" + actual.sha()));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Empty evidence manifest");
        return List.copyOf(result);
    }

    /** Исходные байты читаются один раз; нормализация использует явный контекст захваченного запуска. */
    private record Source(Path path, String sha, String text, Path cashMemory, String node) { }

    /** Не принимает изменённые артефакты, символические ссылки и неверный selftest-префикс. */
    private static Source source(Map<String, Object> value) throws Exception {
        if (!value.keySet().equals(Set.of("path", "sha256", "cashMemory", "node")))
            throw new IllegalArgumentException("Evidence source fields");
        Path path = Path.of(Json.requireString(value, "path"));
        Path home = Path.of(Json.requireString(value, "cashMemory"));
        String sha = Json.requireString(value, "sha256"), node = Json.requireString(value, "node");
        if (!path.isAbsolute() || !home.isAbsolute() || Files.isSymbolicLink(path)
                || !sha.matches("[0-9a-fA-F]{64}")
                || !node.matches("ru/cashprediction/selftest/[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("Evidence source provenance");
        byte[] bytes = Files.readAllBytes(path);
        String observed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!observed.equalsIgnoreCase(sha)) throw new IllegalArgumentException("Evidence SHA mismatch: " + path);
        return new Source(path, observed, new String(bytes, StandardCharsets.UTF_8), home, node);
    }

    /** Проекция проверяет только заявленную область; остальные поля не объявляются совпавшими. */
    private static Object project(UiDump dump, Source source, String scope) {
        Map<String, Object> tree = Json.asObject(DumpTrees.normalized(dump, source.cashMemory(), source.node()), "dump");
        if (Set.of("offline", "stopped", "crashed").contains(scope))
            return Map.of("screens", Map.of()); // Отсутствие проверено на настоящем native dump в load.
        if (scope.equals("narrow-toolbar")) {
            if (dump.frame() == null || dump.frame().contentWidth() <= 0 || dump.frame().contentWidth() >= 1200
                    || dump.toolbar() == null) throw new IllegalArgumentException("Measured narrow frame required");
            return Map.of("frame", Map.of("contentWidth", dump.frame().contentWidth()),
                    "toolbar", Map.of("wrap", dump.toolbar().wrap()));
        }
        String purpose = switch (scope) {
            case "halt-header" -> "simulateHalt";
            case "snapshot" -> "lastSnapshot";
            case "replace-file" -> "replaceFile";
            case "uncaught-buttons" -> "uncaught";
            default -> throw new IllegalArgumentException(scope);
        };
        var alerts = Json.asObject(tree.get("alerts"), "alerts");
        Object raw = alerts.get(purpose);
        if (raw == null) {
            if (!scope.equals("replace-file") || !dump.client().equals("fx"))
                throw new IllegalArgumentException("Missing evidence alert: " + purpose);
            return Map.of("alerts", Map.of());
        }
        var alert = Json.asObject(raw, "alert");
        Object selected = switch (scope) {
            case "halt-header" -> Map.of("header", alert.get("header"));
            case "snapshot" -> Map.of("content", alert.get("content"), "details", alert.get("details"),
                    "detailsExpanded", alert.get("detailsExpanded"));
            case "uncaught-buttons" -> {
                var buttons = new LinkedHashMap<>(Json.asObject(alert.get("buttons"), "buttons"));
                buttons.remove("$order"); // Контракт §10 №10 касается наличия именованных кнопок, не геометрии сообщения.
                yield Map.of("buttons", buttons);
            }
            case "replace-file" -> alert;
            default -> throw new IllegalArgumentException(scope);
        };
        return Map.of("alerts", Map.of(purpose, selected));
    }

    /** Проверяет самостоятельный post-exit DOM-контракт до передачи реального объекта screen в DumpDiff. */
    static Object screen(Map<String, Object> value, String kind) {
        if (!value.keySet().equals(Set.of("kind", "title", "text", "buttons", "open", "mainInert")))
            throw new IllegalArgumentException("Screen observation fields");
        String prefix = kind.equals("offline") ? "offline" : "offline." + kind;
        if (!Set.of("offline", "stopped", "crashed").contains(kind) || !kind.equals(value.get("kind"))
                || !UiText.get(prefix + ".title").equals(value.get("title"))
                || !UiText.get(prefix + ".text").equals(value.get("text"))
                || !Boolean.TRUE.equals(value.get("open")) || !Boolean.TRUE.equals(value.get("mainInert")))
            throw new IllegalArgumentException("Invalid real screen state: " + kind);
        List<Object> buttons = Json.list(value, "buttons");
        if (buttons.size() != (kind.equals("offline") ? 1 : 0)) throw new IllegalArgumentException("Screen buttons");
        for (Object raw : buttons) {
            Map<String, Object> button = Json.asObject(raw, "screen button");
            if (!button.keySet().equals(Set.of("id", "text", "enabled"))
                    || !"offline.retry".equals(button.get("id")) || !Boolean.TRUE.equals(button.get("enabled"))
                    || !UiText.get("offline.retry").equals(button.get("text")))
                throw new IllegalArgumentException("Screen retry button");
        }
        return Map.of("screens", Map.of(kind, value));
    }
}
