package ru.cashprediction.updatetool;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.update.model.DeltaPatch;
import ru.cashprediction.core.update.model.InstalledVersion;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/**
 * Сборочный CLI обновлений, не являющийся четвёртым клиентом приложения.
 * Все операции дерева и форматы обновления делегируются замороженному API ядра.
 * Команды и их именованные параметры перечислены в {@link #help(PrintStream)}.
 */
public final class UpdateTool {
    private UpdateTool() { }

    /**
     * Точка входа именованного модуля {@code ru.cashprediction.updatetool}.
     * Код завершения: 0 - выполнено, 2 - неверные параметры, 1 - ошибка данных или ввода-вывода.
     * @param args команда и именованные параметры
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Выполняет команду без завершения JVM, чтобы тесты проверяли тот же маршрут CLI.
     * Результаты пишутся в указанные файлы; stdout используется только для справки.
     * Ошибки в stderr содержат технический ASCII-код, без UI-уведомлений и содержимого входов.
     * @param args команда и пары параметр/значение
     * @param out стандартный вывод
     * @param err диагностический вывод
     * @return 0 при успехе, 2 при ошибке параметров, 1 при ошибке операции
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            args = decodeArguments(args);
            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("help"))) {
                help(out);
                return 0;
            }
            Arguments options = Arguments.parse(args);
            switch (options.command()) {
                case "inventory" -> inventory(options);
                case "create" -> create(options);
                case "apply" -> apply(options);
                case "verify" -> verify(options);
                case "manifest" -> manifest(options);
                default -> throw new UsageException();
            }
            return 0;
        } catch (UsageException ex) {
            err.println("UPDATE_TOOL_USAGE");
            help(err);
            return 2;
        } catch (IOException | RuntimeException ex) {
            // Сообщения ядра могут быть локализованы, поэтому наружу выходит только код.
            String code = ex.getMessage();
            err.println("UPDATE_TOOL_FAILED " + (code != null && code.matches("[A-Z][A-Z0-9_]{0,79}")
                    ? code : ex.getClass().getSimpleName()));
            return 1;
        }
    }

    /**
     * Описывает точный CLI и условия записи артефактов сборки.
     * @param out поток справки
     */
    public static void help(PrintStream out) {
        out.println("java --module-path <jars> -m ru.cashprediction.updatetool/ru.cashprediction.updatetool.UpdateTool <command>");
        out.println("inventory --root <dir> --out <json>");
        out.println("create --base <dir> --base-release <n> --base-commit <sha> --target <dir> --manifest <update.json> --out <cpdelta>");
        out.println("apply --base <dir> --base-release <n> --base-commit <sha> --patch <cpdelta> --manifest <update.json> --out <new-dir>");
        out.println("verify --root <dir> --manifest <update.json>");
        out.println("manifest --root <dir> --archive <zip> --release <n> --commit <sha> --version <text> --published-at <ISO-Instant> --out <update.json> [--delta <descriptor.json>] [--delta <descriptor.json>]");
        out.println("Outputs must not exist; their parent directory must exist. Outputs must be outside input trees.");
        out.println("Inputs and existing ancestors must not be links/reparse points. Unsafe paths are rejected.");
        out.println("Manifest validates the full ZIP against the root inventory. Delta descriptors use the schema-2 wire fields.");
        out.println("Optional transport: --arguments-base64 <Base64 of UTF-8 JSON string array>; normal command validation still applies.");
        out.println("Exit codes: 0 success/help, 2 invalid arguments, 1 invalid data or I/O failure. Diagnostics: stderr.");
    }

    /**
     * Внешний Windows java.exe может передать Unicode через ANSI-кодировку даже из argfile.
     * ASCII-конверт восстанавливает точные аргументы внутри JVM, не ослабляя последующий разбор путей.
     * Разрешён только один ограниченный конверт: вложенные конверты и некорректный UTF-8 отвергаются.
     */
    private static String[] decodeArguments(String[] args) {
        if (args.length == 0 || !"--arguments-base64".equals(args[0])) return args;
        if (args.length != 2 || args[1] == null || args[1].isEmpty() || args[1].length() > 262144) {
            throw new UsageException();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(args[1]);
            if (!Base64.getEncoder().encodeToString(bytes).equals(args[1])) throw new UsageException();
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (!(JsonParser.parse(json) instanceof List<?> values) || values.isEmpty() || values.size() > 64) {
                throw new UsageException();
            }
            String[] decoded = new String[values.size()];
            for (int i = 0; i < values.size(); i++) {
                if (!(values.get(i) instanceof String value) || value.length() > 32768 || value.indexOf('\0') >= 0) {
                    throw new UsageException();
                }
                decoded[i] = value;
            }
            if ("--arguments-base64".equals(decoded[0])) throw new UsageException();
            return decoded;
        } catch (CharacterCodingException | RuntimeException error) {
            throw new UsageException();
        }
    }

    private static void inventory(Arguments a) throws IOException {
        Path root = a.path("root");
        Path output = a.path("out");
        ToolFiles.output(output, List.of(root), List.of());
        var files = TreeDeltaEngine.inventory(root);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (var file : files) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", file.path());
            entry.put("sizeBytes", file.sizeBytes());
            entry.put("sha256", file.sha256());
            entry.put("readOnly", file.readOnly());
            entries.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", entries);
        result.put("treeSha256", TreeDeltaEngine.treeHash(files));
        ToolFiles.write(output, JsonWriter.write(result));
    }

    private static InstalledVersion base(Arguments a, Path root) throws IOException {
        return new InstalledVersion(a.number("base-release"), a.commit("base-commit"),
                TreeDeltaEngine.treeHash(TreeDeltaEngine.inventory(root)));
    }

    private static void create(Arguments a) throws IOException {
        Path baseRoot = a.path("base");
        Path targetRoot = a.path("target");
        Path input = a.path("manifest");
        Path output = a.path("out");
        ToolFiles.output(output, List.of(baseRoot, targetRoot), List.of(input));
        UpdateManifest target = UpdateCodec.read(ToolFiles.json(input));
        InstalledVersion installed = base(a, baseRoot);
        requireForward(installed, target);
        TreeDeltaEngine.verify(targetRoot, target.files(), target.treeSha256());
        Path scratch = Files.createTempDirectory(output.getParent(), ".cp-tool-");
        try {
            Path patch = scratch.resolve("patch.cpdelta");
            TreeDeltaEngine.create(baseRoot, installed, targetRoot, target, patch);
            ToolFiles.forceAndPublish(patch, output);
        } finally {
            ToolFiles.removeScratch(scratch);
        }
    }

    private static void apply(Arguments a) throws IOException {
        Path baseRoot = a.path("base");
        Path patch = a.path("patch");
        Path input = a.path("manifest");
        Path output = a.path("out");
        ToolFiles.output(output, List.of(baseRoot), List.of(patch, input));
        UpdateManifest target = UpdateCodec.read(ToolFiles.json(input));
        InstalledVersion installed = base(a, baseRoot);
        requireForward(installed, target);
        var descriptor = target.deltaPatches().stream().filter(delta ->
                delta.baseReleaseNumber() == installed.releaseNumber()
                && delta.baseCommitSha().equals(installed.commitSha())
                && delta.baseTreeSha256().equals(installed.treeSha256())).findFirst()
                .orElseThrow(() -> new IOException("PATCH_BASE_NOT_DECLARED"));
        ToolFiles.checkDigest(patch, descriptor.sizeBytes(), descriptor.sha256());
        Path scratch = Files.createTempDirectory(output.getParent(), ".cp-tool-");
        try {
            Path tree = scratch.resolve("tree");
            TreeDeltaEngine.apply(baseRoot, installed, patch, target, tree);
            TreeDeltaEngine.verify(tree, target.files(), target.treeSha256());
            ToolFiles.checkAncestors(output);
            Files.move(tree, output);
        } finally {
            ToolFiles.removeScratch(scratch);
        }
    }

    private static void verify(Arguments a) throws IOException {
        UpdateManifest target = UpdateCodec.read(ToolFiles.json(a.path("manifest")));
        TreeDeltaEngine.verify(a.path("root"), target.files(), target.treeSha256());
    }

    private static void manifest(Arguments a) throws IOException {
        Path root = a.path("root");
        Path archive = a.path("archive");
        Path output = a.path("out");
        List<Path> descriptors = new ArrayList<>();
        for (String value : a.values("delta")) descriptors.add(ToolFiles.path(value));
        List<Path> inputs = new ArrayList<>(descriptors);
        inputs.add(archive);
        ToolFiles.output(output, List.of(root), inputs);
        var files = TreeDeltaEngine.inventory(root);
        var digest = ToolFiles.digest(archive);
        List<DeltaPatch> deltas = new ArrayList<>();
        // Проверяем исходные JSON-типы до записи: иначе 1e0 превращается в допустимое целое 1.
        for (Path descriptor : descriptors) {
            deltas.add(UpdateCodec.readDelta(UpdateCodec.object(UpdateCodec.parse(ToolFiles.json(descriptor)))));
        }
        UpdateManifest target = new UpdateManifest(a.number("release"), a.commit("commit"),
                a.value("version"), a.instant("published-at"), archive.getFileName().toString(),
                digest.size(), digest.sha256(), TreeDeltaEngine.treeHash(files), files, deltas);
        String json = UpdateCodec.write(target);
        Path scratch = Files.createTempDirectory(output.getParent(), ".cp-tool-");
        try {
            TreeDeltaEngine.extractFull(archive, target, scratch.resolve("tree"));
            TreeDeltaEngine.verify(root, target.files(), target.treeSha256());
            // Исключаем изменение архива между хешированием и проверкой содержимого.
            ToolFiles.checkDigest(archive, target.sizeBytes(), target.sha256());
            ToolFiles.write(output, json);
        } finally {
            ToolFiles.removeScratch(scratch);
        }
    }

    private static void requireForward(InstalledVersion base, UpdateManifest target) throws IOException {
        if (base.releaseNumber() >= target.releaseNumber()) throw new IOException("NON_FORWARD_RELEASE");
    }

    /** Ошибка синтаксиса CLI, отделённая от повреждения данных. */
    private static final class UsageException extends RuntimeException { }

    /** Разобранные именованные параметры одной команды. */
    private record Arguments(String command, Map<String, List<String>> options) {
        static Arguments parse(String[] args) {
            if (args.length == 0 || args.length % 2 == 0) throw new UsageException();
            List<String> required = switch (args[0]) {
                case "inventory" -> List.of("root", "out");
                case "create" -> List.of("base", "base-release", "base-commit", "target", "manifest", "out");
                case "apply" -> List.of("base", "base-release", "base-commit", "patch", "manifest", "out");
                case "verify" -> List.of("root", "manifest");
                case "manifest" -> List.of("root", "archive", "release", "commit", "version", "published-at", "out");
                default -> throw new UsageException();
            };
            Map<String, List<String>> options = new LinkedHashMap<>();
            for (int i = 1; i < args.length; i += 2) {
                if (!args[i].startsWith("--")) throw new UsageException();
                String name = args[i].substring(2);
                boolean delta = args[0].equals("manifest") && name.equals("delta");
                if ((!required.contains(name) && !delta) || args[i + 1].isEmpty()
                        || args[i + 1].startsWith("--")) throw new UsageException();
                List<String> values = options.computeIfAbsent(name, ignored -> new ArrayList<>());
                if (values.size() >= (delta ? 2 : 1)) throw new UsageException();
                values.add(args[i + 1]);
            }
            if (!options.keySet().containsAll(required)) throw new UsageException();
            Arguments result = new Arguments(args[0], options);
            for (String key : List.of("release", "base-release")) if (options.containsKey(key)) result.number(key);
            for (String key : List.of("commit", "base-commit")) if (options.containsKey(key)) result.commit(key);
            if (options.containsKey("published-at")) result.instant("published-at");
            return result;
        }

        String value(String name) { return options.get(name).getFirst(); }
        List<String> values(String name) { return options.getOrDefault(name, List.of()); }
        Path path(String name) throws IOException { return ToolFiles.path(value(name)); }
        int number(String name) {
            String text = value(name);
            if (!text.matches("[1-9][0-9]*")) throw new UsageException();
            try { return Integer.parseInt(text); }
            catch (NumberFormatException ex) { throw new UsageException(); }
        }
        String commit(String name) {
            String text = value(name);
            if (!text.matches("[0-9a-f]{40}")) throw new UsageException();
            return text;
        }
        Instant instant(String name) {
            try { return Instant.parse(value(name)); }
            catch (java.time.format.DateTimeParseException ex) { throw new UsageException(); }
        }
    }
}
