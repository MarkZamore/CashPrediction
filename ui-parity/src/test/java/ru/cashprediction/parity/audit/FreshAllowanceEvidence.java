package ru.cashprediction.parity.audit;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.json.UiJson;

/** Собирает неизменный манифест только из результатов отдельного свежего запуска. */
public final class FreshAllowanceEvidence {
    private FreshAllowanceEvidence() { }

    /** Командная точка входа: вход, новый output и UUID текущего ведущего запуска. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected input, output and current run UUID");
        requireRunIdentity(Path.of(args[0]), args[2]);
        aggregate(Path.of(args[0]), Path.of(args[1]));
    }

    /** Проверяет все наблюдения, копирует исходные байты и закрепляет происхождение каждого файла SHA-256. */
    @SuppressWarnings("unchecked")
    public static Path aggregate(Path input, Path output) throws Exception {
        input = input.toRealPath();
        var receipt = receipt(input);
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists");
        Files.createDirectories(output);
        output = output.toRealPath();
        var contexts = new LinkedHashMap<String, Map<String, Object>>();
        var roots = new LinkedHashMap<String, Path>();
        try (var paths = Files.walk(input)) {
            for (Path p : paths.filter(p -> p.getFileName().toString().equals("reference-context.json")
                    || p.getFileName().toString().equals("probe.json")).toList()) {
                requireFresh(p, input, receipt.started());
                var data = (Map<String, Object>) JsonParser.parse(Files.readString(p));
                String key = data.containsKey("name") ? "web:" + data.get("name")
                        : data.get("client") + ":" + data.getOrDefault("role", "native");
                if (contexts.putIfAbsent(key, data) != null) throw new IllegalArgumentException("Duplicate observation " + key);
                roots.put(key, p.getParent());
            }
        }
        var required = new HashSet<String>(List.of("fx:native", "swing:native",
                "fx:controlled-absent", "swing:controlled-absent", "fx:controlled-identical-archive", "swing:controlled-identical-archive"));
        for (String name : List.of("halt-cancel", "snapshot-absent", "snapshot-prepared", "replace-file", "narrow-toolbar", "js-error", "offline", "stopped", "crashed")) required.add("web:" + name);
        if (!contexts.keySet().equals(required)) throw new IllegalArgumentException("Incomplete observations " + contexts.keySet());
        for (String key : required) {
            Path root = roots.get(key);
            verifyFrozenJars(root, receipt.hashes(), key.startsWith("web:") ? "web" : key.substring(0, key.indexOf(':')));
            if (!key.startsWith("web:")) {
                Path cashMemory = Path.of(contexts.get(key).get("cashMemory").toString()).toAbsolutePath().normalize();
                if (!cashMemory.startsWith(input)) throw new IllegalArgumentException("Foreign CashMemory context " + key);
            }
            if (key.startsWith("web:")) {
                String name = key.substring(4);
                requireFresh(root.resolve("result.txt"), input, receipt.started());
                requireFresh(root.resolve("actions.jsonl"), input, receipt.started());
                if (!Files.readString(root.resolve("result.txt")).equals("PASS " + name + "\n")) throw new IllegalArgumentException("Probe not completed " + key);
                if (!Files.isRegularFile(root.resolve("actions.jsonl"))) throw new IllegalArgumentException("Missing action journal");
            } else {
                Path log = root.resolve("dumps/selftest.log");
                requireFresh(log, input, receipt.started());
                var lines = Files.readAllLines(log);
                Path cps = root.resolve(key.endsWith(":native") ? "native-allowances.cps" : "controlled-snapshot.cps");
                var commands = ru.cashprediction.core.ui.selftest.SelfTestScript.load(cps.toString()).lines();
                if (lines.size() != commands.size() + 1 || !lines.getLast().equals("SELFTEST DONE"))
                    throw new IllegalArgumentException("Native journal incomplete " + key);
                for (int i = 0; i < commands.size(); i++) {
                    var command = commands.get(i);
                    if (!lines.get(i).equals("SELFTEST " + command.number() + " OK " + command.text()))
                        throw new IllegalArgumentException("Native command not confirmed " + key);
                }
                if (!key.endsWith(":native")) {
                    String role = key.endsWith("absent") ? "absent" : "prepared";
                    if (!Files.readString(root.getParent().resolve("result.txt")).equals("PASS " + role + "\n"))
                        throw new IllegalArgumentException("Controlled fixture not completed " + key);
                }
            }
        }
        var pairs = new ArrayList<Map<String, Object>>();
        var provenance = new LinkedHashMap<String, Object>();
        final Path inputPath = input, outputPath = output;
        class Sources {
            final Map<String, Map<String, Object>> cached = new HashMap<>();
            Map<String, Object> source(String key, String relative) throws Exception {
                String identity = key + "/" + relative;
                if (cached.containsKey(identity)) return cached.get(identity);
                Path original = roots.get(key).resolve(relative).normalize();
                if (!original.startsWith(inputRoot) || Files.isSymbolicLink(original)
                        || !original.toRealPath().startsWith(inputRoot)) throw new IllegalArgumentException("Unsafe source");
                byte[] bytes = Files.readAllBytes(original);
                requireFresh(original, inputRoot, receipt.started());
                if (!relative.startsWith("screen-")) {
                    var dump = ru.cashprediction.parity.pipeline.DumpTrees.read(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    String client = key.substring(0, key.indexOf(':'));
                    String step = original.getFileName().toString().replaceFirst("\\.json$", "");
                    if (dump.schema() != 1 || !dump.client().equals(client) || !dump.step().equals(step))
                        throw new IllegalArgumentException("Observation identity mismatch " + original);
                }
                Path copy = outputRoot.resolve("sources/" + key.replace(':', '-') + "/" + relative);
                Files.createDirectories(copy.getParent()); Files.write(copy, bytes, StandardOpenOption.CREATE_NEW);
                var context = contexts.get(key);
                String home = key.startsWith("web:") ? roots.get(key).resolve("home/CashMemory").toString() : context.get("cashMemory").toString();
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                provenance.put(outputRoot.relativize(copy).toString(), Map.of("original", original.toString(), "sha256", sha,
                        "context", context, "size", bytes.length));
                Map<String, Object> result = Map.of("path", copy.toString(), "sha256", sha, "cashMemory", home, "node", context.get("node"));
                cached.put(identity, result); return result;
            }
            void pair(String label, String scope, String left, String lp, String right, String rp) throws Exception {
                pairs.add(Map.of("label", label, "scope", scope, "reference", source(left, lp), "actual", source(right, rp)));
            }
            final Path inputRoot = inputPath, outputRoot = outputPath;
        }
        // Локальные final-значения не позволяют изменить границы проверки во время сбора.
        Sources s = new Sources();
        s.pair("halt-web", "halt-header", "fx:native", "dumps/native-allowances/halt-question.json", "web:halt-cancel", "halt-question.json");
        for (String role : List.of("absent", "identical-archive")) {
            String nativeKey = "controlled-" + role;
            String webRole = role.equals("absent") ? "absent" : "prepared";
            s.pair("snapshot-native-" + webRole, "snapshot", "fx:" + nativeKey, "dumps/controlled-snapshot/controlled-snapshot.json", "swing:" + nativeKey, "dumps/controlled-snapshot/controlled-snapshot.json");
            s.pair("snapshot-web-" + webRole, "snapshot", "fx:" + nativeKey, "dumps/controlled-snapshot/controlled-snapshot.json", "web:snapshot-" + webRole, "snapshot-" + webRole + ".json");
        }
        s.pair("replace-native", "replace-file", "fx:native", "dumps/native-allowances/replace-file.json", "swing:native", "dumps/native-allowances/replace-file.json");
        s.pair("replace-web", "replace-file", "fx:native", "dumps/native-allowances/replace-file.json", "web:replace-file", "replace-file.json");
        s.pair("uncaught-web", "uncaught-buttons", "fx:native", "dumps/native-allowances/uncaught-reference.json", "web:js-error", "js-error.json");
        s.pair("narrow-web", "narrow-toolbar", "fx:native", "dumps/native-allowances/narrow-toolbar.json", "web:narrow-toolbar", "narrow-toolbar.json");
        for (String kind : List.of("offline", "stopped", "crashed")) s.pair(kind + "-web", kind, "fx:native", "dumps/native-allowances/prepared-sample.json", "web:" + kind, "screen-" + kind + ".json");
        Path manifest = output.resolve("evidence-manifest.json");
        Path pending = output.resolve("evidence-pending.json");
        Files.writeString(pending, UiJson.write(Map.of("pairs", pairs)), StandardOpenOption.CREATE_NEW);
        if (AllowanceEvidence.load(pending).size() != 12) throw new IllegalStateException("Expected twelve real pairs");
        var frozenInputs = new TreeMap<String, Object>();
        for (var entry : roots.entrySet()) {
            try (var files = Files.walk(entry.getValue())) {
                for (Path jar : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                    if (Files.isSymbolicLink(jar) || !jar.toRealPath().startsWith(input)) throw new IllegalArgumentException("Unsafe frozen jar");
                    frozenInputs.put(input.relativize(jar).toString(), Map.of("sha256", HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))), "size", Files.size(jar)));
                }
            }
        }
        Files.writeString(output.resolve("provenance.json"), UiJson.write(Map.of("receipt", receipt.data(), "files", provenance,
                "frozenInputs", frozenInputs)), StandardOpenOption.CREATE_NEW);
        Files.move(pending, manifest, StandardCopyOption.ATOMIC_MOVE);
        return manifest;
    }

    /** Квитанция связывает входной каталог, начало запуска и четыре production-сборки. */
    record Receipt(long started, Map<String, String> hashes, Map<String, Object> data) { }

    /** UUID передаётся текущим ведущим скриптом, а не извлекается автоматически из старых данных. */
    static void requireRunIdentity(Path input, String expected) throws Exception {
        if (expected == null || !expected.matches("[0-9a-fA-F-]{36}")
                || !expected.equals(receipt(input).data().get("runId")))
            throw new IllegalArgumentException("Foreign run receipt");
    }

    /** Старый набор наблюдений без квитанции нового запуска не принимается. */
    @SuppressWarnings("unchecked")
    static Receipt receipt(Path input) throws Exception {
        if (!Files.isRegularFile(input.resolve("fresh-run.json"))) throw new IllegalArgumentException("Missing fresh run receipt");
        var value = (Map<String, Object>) JsonParser.parse(Files.readString(input.resolve("fresh-run.json")));
        if (!value.keySet().equals(Set.of("schema", "runId", "input", "startedAtEpochMillis", "sources"))
                || ((Number) value.get("schema")).intValue() != 1
                || !value.get("runId").toString().matches("[0-9a-fA-F-]{36}")
                || !Path.of(value.get("input").toString()).toRealPath().equals(input.toRealPath()))
            throw new IllegalArgumentException("Invalid fresh run receipt");
        long started = ((Number) value.get("startedAtEpochMillis")).longValue();
        if (started <= 0 || started > System.currentTimeMillis()) throw new IllegalArgumentException("Invalid run time");
        var hashes = new LinkedHashMap<String, String>();
        for (Object raw : (List<?>) value.get("sources")) {
            var source = (Map<String, Object>) raw;
            String name = source.get("name").toString(), sha = source.get("sha256").toString();
            if (!source.keySet().equals(Set.of("name", "sha256")) || !sha.matches("[0-9a-f]{64}")
                    || !name.matches("cashprediction-(core|ui-fx|ui-swing|web)-[^/\\\\]+\\.jar")
                    || hashes.putIfAbsent(name, sha) != null) throw new IllegalArgumentException("Invalid production source");
        }
        if (hashes.size() != 4) throw new IllegalArgumentException("Four production sources required");
        for (String module : List.of("core", "ui-fx", "ui-swing", "web"))
            if (hashes.keySet().stream().filter(n -> n.startsWith("cashprediction-" + module + "-")).count() != 1)
                throw new IllegalArgumentException("Missing source module " + module);
        return new Receipt(started, Map.copyOf(hashes), value);
    }

    /** Запрещает чужой, ссылочный или созданный до запуска артефакт. */
    static void requireFresh(Path file, Path input, long started) throws Exception {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || !file.toRealPath().startsWith(input.toRealPath())
                || Files.getLastModifiedTime(file).toMillis() < started)
            throw new IllegalArgumentException("Not a fresh owned artifact: " + file);
    }

    /** Каждый collector должен использовать именно объявленные production-байты. */
    static void verifyFrozenJars(Path root, Map<String, String> expected, String client) throws Exception {
        var observed = new HashMap<String, String>();
        try (var files = Files.walk(root)) {
            for (Path jar : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                String name = jar.getFileName().toString();
                if (expected.containsKey(name)) {
                    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
                    if (!sha.equals(expected.get(name)) || observed.putIfAbsent(name, sha) != null)
                        throw new IllegalArgumentException("Changed or duplicate frozen jar " + jar);
                }
            }
        }
        String module = client.equals("fx") ? "ui-fx" : client.equals("swing") ? "ui-swing" : "web";
        var required = expected.keySet().stream().filter(n -> n.startsWith("cashprediction-core-")
                || n.startsWith("cashprediction-" + module + "-")).collect(java.util.stream.Collectors.toSet());
        if (!observed.keySet().equals(required)) throw new IllegalArgumentException("Missing frozen production jars " + root);
    }
}
