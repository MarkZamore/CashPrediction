package ru.cashprediction.parity.update;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.parity.process.ProcessTree;

/** Репетиция реального frozen CLI от двух независимых баз через локальный HTTP, без UI. */
public final class TwoBaseRehearsal {
    private final String toolClasspath;
    private final Path evidence;
    private final List<Map<String, Object>> commands = new ArrayList<>();
    private TwoBaseRehearsal(String toolClasspath, Path evidence) {
        this.toolClasspath = toolClasspath; this.evidence = evidence;
    }

    /** Запускается только с уже собранными main классами; ничего не компилирует. */
    public static void main(String[] args) throws Exception {
        if (args.length != 12) throw new IllegalArgumentException("REHEARSAL_ARGUMENTS");
        Path work = Path.of(args[1]); Path evidence = Path.of(args[2]);
        if (!Files.isDirectory(work) || !Files.isDirectory(evidence) || Files.exists(evidence.resolve("results.json"))) {
            throw new IOException("OWNED_EMPTY_DIRECTORIES_REQUIRED");
        }
        TwoBaseRehearsal run = new TwoBaseRehearsal(args[0], evidence);
        List<Map<String, Object>> rows = UpdateEvidence.pending();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1); report.put("status", "PENDING"); report.put("cells", rows);
        report.put("rehearsalScope", "CLI_HTTP_ONLY"); report.put("portableExecuted", false);
        report.put("inputs", List.of(
                Map.of("id", "B1", "root", Path.of(args[3]).toAbsolutePath().toString(), "release", Integer.parseInt(args[4]), "commit", args[5]),
                Map.of("id", "B2", "root", Path.of(args[6]).toAbsolutePath().toString(), "release", Integer.parseInt(args[7]), "commit", args[8]),
                Map.of("id", "T", "root", Path.of(args[9]).toAbsolutePath().toString(), "release", Integer.parseInt(args[10]), "commit", args[11])));
        report.put("toolCommands", run.commands);
        try {
            for (int variant = 0; variant < 3; variant++) {
                String relative = List.of("plain", "Мои программы", "Δ 测试").get(variant);
                Path container = Files.createDirectories(work.resolve(relative));
                Path target = copy(Path.of(args[9]), container.resolve("T"));
                Path[] bases = {copy(Path.of(args[3]), container.resolve("B1")), copy(Path.of(args[6]), container.resolve("B2"))};
                for (var row : rows) if (row.get("path").equals(UpdateEvidence.PATHS.get(variant))) {
                    int b = row.get("base").equals("B1") ? 0 : 1;
                    row.put("exe", bases[b].resolve(exe((String) row.get("client"))).toString());
                    row.put("args", row.get("client").equals("web") ? List.of("--no-browser", "--no-window") : List.of());
                    row.put("baseRelease", Integer.parseInt(args[b == 0 ? 4 : 7])); row.put("baseCommit", args[b == 0 ? 5 : 8]);
                    row.put("targetRelease", Integer.parseInt(args[10])); row.put("targetCommit", args[11]);
                }
                run.rehearse(container, bases, target, args);
            }
            report.put("rehearsal", "PASS");
        } catch (Exception failure) {
            report.put("rehearsal", "FAIL"); report.put("failure", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            Files.writeString(evidence.resolve("results.json"), JsonWriter.write(report));
        }
    }

    private static String exe(String client) {
        return switch (client) { case "fx" -> "CashPrediction.exe"; case "swing" -> "CashPrediction-Swing.exe"; case "web" -> "CashPrediction-Web.exe"; default -> throw new IllegalArgumentException("CLIENT"); };
    }

    private static Path copy(Path source, Path destination) throws IOException {
        FixtureAuthority.noLinks(source);
        Files.createDirectory(destination);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (path.equals(source)) continue;
                String relative = source.relativize(path).toString().replace('\\', '/');
                if (relative.equals("CashMemory") || relative.startsWith("CashMemory/")) continue;
                Path out = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectory(out);
                else Files.copy(path, out, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        for (String client : UpdateEvidence.CLIENTS) if (!Files.isRegularFile(destination.resolve(exe(client)))) throw new IOException("PORTABLE_EXE_MISSING");
        Files.createDirectories(destination.resolve("CashMemory"));
        Files.writeString(destination.resolve("CashMemory/user.md"), "fixture-user-data\n");
        Files.writeString(destination.resolve("unmanaged.txt"), "fixture-unmanaged\n");
        return destination;
    }

    private void rehearse(Path container, Path[] bases, Path target, String[] args) throws Exception {
        var targetBefore = FixtureAuthority.managed(target);
        var targetUser = FixtureAuthority.user(target);
        Path zip = container.resolve("CashPrediction-portable.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var entry : targetBefore) {
                ZipEntry item = new ZipEntry("CashPrediction/" + entry.path()); item.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                out.putNextEntry(item); Files.copy(target.resolve(entry.path()), out); out.closeEntry();
            }
        }
        Path initial = container.resolve("initial.json");
        List<String> manifestArgs = List.of("manifest", "--root", target.toString(), "--archive", zip.toString(),
                "--release", args[10], "--commit", args[11], "--version", "rehearsal", "--published-at", "2026-10-03T00:00:00Z");
        List<String> first = new ArrayList<>(manifestArgs); first.addAll(List.of("--out", initial.toString())); cli(first);
        UpdateManifest initialManifest = UpdateCodec.read(Files.readString(initial));
        if (!initialManifest.files().equals(targetBefore) || !initialManifest.treeSha256().equals(FixtureAuthority.treeHash(targetBefore))) throw new IOException("INDEPENDENT_TARGET_MISMATCH");
        List<Path> patches = new ArrayList<>(); List<Path> descriptors = new ArrayList<>();
        for (int b = 0; b < 2; b++) {
            Path base = bases[b]; String release = args[b == 0 ? 4 : 7]; String commit = args[b == 0 ? 5 : 8];
            var before = FixtureAuthority.managed(base); var user = FixtureAuthority.user(base);
            Path inventory = container.resolve("inventory-" + b + ".json");
            cli(List.of("inventory", "--root", base.toString(), "--out", inventory.toString()));
            var inv = UpdateCodec.object(UpdateCodec.parse(Files.readString(inventory)));
            if (!UpdateCodec.readFiles(inv.get("files")).equals(before)
                    || !FixtureAuthority.treeHash(before).equals(inv.get("treeSha256"))) throw new IOException("INDEPENDENT_BASE_MISMATCH");
            Path patch = container.resolve("CashPrediction.from-" + release + ".cpdelta");
            List<String> create = List.of("create", "--base", base.toString(), "--base-release", release, "--base-commit", commit,
                    "--target", target.toString(), "--manifest", initial.toString());
            var command = new ArrayList<>(create); command.addAll(List.of("--out", patch.toString())); cli(command);
            Path repeat = container.resolve("repeat-" + b + ".cpdelta");
            command = new ArrayList<>(create); command.addAll(List.of("--out", repeat.toString())); cli(command);
            if (Files.mismatch(patch, repeat) != -1) throw new IOException("NONDETERMINISTIC_DELTA");
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("algorithm", "cashprediction-tree-delta"); descriptor.put("algorithmVersion", 1);
            descriptor.put("baseReleaseNumber", Integer.parseInt(release)); descriptor.put("baseCommitSha", commit);
            descriptor.put("baseTreeSha256", FixtureAuthority.treeHash(before)); descriptor.put("assetName", patch.getFileName().toString());
            descriptor.put("sizeBytes", Files.size(patch)); descriptor.put("sha256", FixtureAuthority.sha(patch));
            Path d = container.resolve("descriptor-" + b + ".json"); Files.writeString(d, JsonWriter.write(descriptor));
            descriptors.add(d); patches.add(patch);
            if (!before.equals(FixtureAuthority.managed(base)) || !user.equals(FixtureAuthority.user(base))) throw new IOException("BASE_MUTATED");
        }
        Path manifest = container.resolve("update.json");
        var finalCommand = new ArrayList<>(manifestArgs); finalCommand.addAll(List.of("--out", manifest.toString()));
        for (Path descriptor : descriptors) finalCommand.addAll(List.of("--delta", descriptor.toString())); cli(finalCommand);
        try (LocalUpdateServer server = new LocalUpdateServer(); HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            server.put("/update.json", new LocalUpdateServer.Reply(200, Files.readAllBytes(manifest), null));
            for (Path patch : patches) server.putFile("/" + patch.getFileName(), patch);
            server.putFile("/CashPrediction-portable.zip", zip);
            for (int b = 0; b < 2; b++) {
                Path base = bases[b]; var before = FixtureAuthority.managed(base); var user = FixtureAuthority.user(base);
                byte[] metadata = get(http, server.manifestUri());
                UpdateManifest parsed = UpdateCodec.read(new String(metadata, java.nio.charset.StandardCharsets.UTF_8));
                Path patch = patches.get(b); Path local = container.resolve("download-" + b + ".cpdelta");
                var response = http.send(HttpRequest.newBuilder(server.manifestUri().resolve(patch.getFileName().toString()))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofFile(local));
                if (response.statusCode() != 200 || !parsed.deltaPatches().get(b).sha256().equals(FixtureAuthority.sha(local))) throw new IOException("HTTP_DELTA_DIGEST");
                Path out = container.resolve("applied-" + b);
                cli(List.of("apply", "--base", base.toString(), "--base-release", args[b == 0 ? 4 : 7], "--base-commit", args[b == 0 ? 5 : 8],
                        "--patch", local.toString(), "--manifest", manifest.toString(), "--out", out.toString()));
                cli(List.of("verify", "--root", out.toString(), "--manifest", manifest.toString()));
                if (!targetBefore.equals(FixtureAuthority.managed(out)) || !before.equals(FixtureAuthority.managed(base))
                        || !user.equals(FixtureAuthority.user(base))) throw new IOException("ROUNDTRIP_OR_PROTECTION");
                String prefix = container.getFileName() + "-B" + (b + 1);
                Files.writeString(evidence.resolve(prefix + "-trees.json"), JsonWriter.write(Map.of(
                        "currentBefore", UpdateCodec.fileObjects(before), "currentAfter", UpdateCodec.fileObjects(FixtureAuthority.managed(base)),
                        "targetBefore", UpdateCodec.fileObjects(targetBefore), "targetAfter", UpdateCodec.fileObjects(FixtureAuthority.managed(out)),
                        "userBefore", user, "userAfter", FixtureAuthority.user(base))));
            }
            if (server.count("/update.json") != 2 || server.count("/CashPrediction-portable.zip") != 0) throw new IOException("HTTP_COUNT");
            for (Path patch : patches) if (server.count("/" + patch.getFileName()) != 1) throw new IOException("HTTP_PATCH_COUNT");
            Files.writeString(evidence.resolve(container.getFileName() + "-http.json"), JsonWriter.write(server.trace()));
        }
        if (!targetBefore.equals(FixtureAuthority.managed(target)) || !targetUser.equals(FixtureAuthority.user(target))) throw new IOException("TARGET_MUTATED");
    }

    private static byte[] get(HttpClient http, java.net.URI uri) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException("HTTP_STATUS"); return response.body();
    }

    private void cli(List<String> args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-XX:-UsePerfData", "-cp", toolClasspath, "ru.cashprediction.updatetool.UpdateTool")); command.addAll(args);
        int id = commands.size(); Path stdout = evidence.resolve("tool-" + id + ".out.txt"); Path stderr = evidence.resolve("tool-" + id + ".err.txt");
        Map<String, Object> receipt = new LinkedHashMap<>(); receipt.put("command", command); receipt.put("startedAt", Instant.now().toString());
        receipt.put("status", "FAIL"); commands.add(receipt);
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException("TOOL_TIMEOUT");
            receipt.put("exitCode", process.exitValue());
            if (process.exitValue() != 0) throw new IOException("TOOL_FAILED"); receipt.put("status", "PASS");
        } finally {
            if (process.isAlive()) ProcessTree.kill(process, Duration.ofSeconds(5)).requireClean();
            receipt.put("finishedAt", Instant.now().toString()); receipt.put("stdout", stdout.toString()); receipt.put("stderr", stderr.toString());
        }
    }
}
