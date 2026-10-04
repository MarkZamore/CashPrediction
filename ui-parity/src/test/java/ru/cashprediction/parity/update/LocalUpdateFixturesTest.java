package ru.cashprediction.parity.update;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.model.DeltaPatch;
import ru.cashprediction.core.update.model.InstalledVersion;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.net.UpdatePreparer;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;
import static org.junit.jupiter.api.Assertions.*;

/** Конкретные HTTP-фикстуры настоящего preparer; эти тесты не запускают portable UI. */
final class LocalUpdateFixturesTest {
    @TempDir Path temp;

    @Test void bothBasesDownloadTheirOwnDeltaAndNeverPoll() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            for (int b = 0; b < 2; b++) {
                var before = FixtureAuthority.managed(f.bases[b]); var user = FixtureAuthority.user(f.bases[b]);
                try (var prepare = f.preparer(b)) { assertTrue(prepare.prepare()); assertTrue(prepare.prepare()); }
                assertEquals(f.manifest.files(), FixtureAuthority.managed(f.ready(b).resolve("tree")));
                assertEquals(before, FixtureAuthority.managed(f.bases[b])); assertEquals(user, FixtureAuthority.user(f.bases[b]));
                assertEquals(1, f.server.count("/" + f.manifest.deltaPatches().get(b).assetName()));
            }
            assertEquals(2, f.server.count("/update.json")); assertEquals(0, f.server.count("/CashPrediction-portable.zip"));
        }
    }

    @Test void corruptDeltaFallsBackAndCorruptFullKeepsCurrent() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            for (var delta : f.manifest.deltaPatches()) f.server.put("/" + delta.assetName(), new LocalUpdateServer.Reply(200, new byte[]{1}, null));
            var first = FixtureAuthority.managed(f.bases[0]); var user = FixtureAuthority.user(f.bases[0]);
            try (var p = f.preparer(0)) { assertTrue(p.prepare()); }
            assertEquals(f.manifest.files(), FixtureAuthority.managed(f.ready(0).resolve("tree")));
            assertEquals(1, f.server.count("/CashPrediction-portable.zip"));
            f.server.put("/CashPrediction-portable.zip", new LocalUpdateServer.Reply(200, new byte[]{2}, null));
            var second = FixtureAuthority.managed(f.bases[1]);
            try (var p = f.preparer(1)) { assertFalse(p.prepare()); }
            assertFalse(Files.exists(f.ready(1)));
            assertEquals(first, FixtureAuthority.managed(f.bases[0])); assertEquals(second, FixtureAuthority.managed(f.bases[1]));
            assertEquals(user, FixtureAuthority.user(f.bases[0]));
            assertEquals(2, f.server.count("/CashPrediction-portable.zip"));
            for (var delta : f.manifest.deltaPatches()) assertEquals(1, f.server.count("/" + delta.assetName()));
        }
    }

    @Test void unavailableAndMalformedManifestUseExactlyThreeAttempts() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            f.server.put("/update.json", new LocalUpdateServer.Reply(503, new byte[0], null));
            try (var p = f.preparer(0)) { assertFalse(p.prepare()); assertFalse(p.prepare()); }
            assertEquals(3, f.server.count("/update.json"));
            f.server.put("/update.json", new LocalUpdateServer.Reply(200, "{}".getBytes(StandardCharsets.UTF_8), null));
            try (var p = f.preparer(1)) { assertFalse(p.prepare()); }
            assertEquals(6, f.server.count("/update.json")); assertEquals(0, f.server.count("/CashPrediction-portable.zip"));
            assertFalse(Files.exists(f.ready(0))); assertFalse(Files.exists(f.ready(1)));
        }
    }

    @Test void competingPreparationCancellationAndNextSessionAreBounded() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            var pool = Executors.newFixedThreadPool(2);
            CountDownLatch gate = new CountDownLatch(1);
            String asset = "/" + f.manifest.deltaPatches().getFirst().assetName();
            f.server.put(asset, new LocalUpdateServer.Reply(200, f.patchBytes.getFirst(), gate));
            try (var first = f.preparer(0); var other = f.preparer(0)) {
                var task = pool.submit(first::prepare);
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (f.server.count(asset) == 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(1, f.server.count(asset)); assertFalse(other.prepare());
                Path partial = f.bases[0].resolve("CashMemory/Updates/payload.download");
                while ((!Files.isRegularFile(partial) || Files.size(partial) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(Files.isRegularFile(partial) && Files.size(partial) > 0, "REAL_PARTIAL_BODY_REQUIRED");
                assertTimeoutPreemptively(Duration.ofSeconds(3), first::close);
                assertFalse(task.get(3, TimeUnit.SECONDS)); assertFalse(Files.exists(f.ready(0)));
                assertFalse(Files.exists(f.bases[0].resolve("CashMemory/Updates/payload.download")));
                assertEquals(0, f.server.count("/CashPrediction-portable.zip"));
                gate.countDown(); f.server.put(asset, new LocalUpdateServer.Reply(200, f.patchBytes.getFirst(), null));
                try (var next = f.preparer(0)) { assertTrue(next.prepare()); }
                assertEquals(2, f.server.count(asset)); assertEquals(2, f.server.count("/update.json"));
            } finally {
                gate.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void timeoutAndTrueOfflineLeaveNoReady() throws Exception {
        try (Fixture f = new Fixture(temp)) {
            CountDownLatch gate = new CountDownLatch(1);
            f.server.put("/update.json", new LocalUpdateServer.Reply(200, new byte[]{1, 2}, gate));
            try (var p = f.preparer(0, f.server.manifestUri(), Duration.ofMillis(150))) {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertFalse(p.prepare()));
            } finally { gate.countDown(); }
            assertEquals(3, f.server.count("/update.json")); assertFalse(Files.exists(f.ready(0)));
            URI offline = f.server.manifestUri(); f.server.close();
            try (var p = f.preparer(1, offline, Duration.ofMillis(150))) {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertFalse(p.prepare()));
            }
            assertFalse(Files.exists(f.ready(1)));
        }
    }

    /** Независимые деревья и два разных контейнера, связанные проверенной схемой 2. */
    private static final class Fixture implements AutoCloseable {
        final Path[] bases = new Path[2];
        final InstalledVersion[] versions = new InstalledVersion[2];
        final List<byte[]> patchBytes = new ArrayList<>();
        final LocalUpdateServer server;
        final HttpClient client;
        final UpdateManifest manifest;

        Fixture(Path root) throws Exception {
            Path target = tree(root.resolve("T"), "target");
            var files = FixtureAuthority.managed(target); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (var file : files) {
                    var entry = new ZipEntry("CashPrediction/" + file.path()); entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                    zip.putNextEntry(entry); Files.copy(target.resolve(file.path()), zip); zip.closeEntry();
                }
            }
            byte[] full = bytes.toByteArray();
            var initial = new UpdateManifest(3, "c".repeat(40), "3", Instant.EPOCH, "CashPrediction-portable.zip",
                    full.length, FixtureAuthority.sha(full), FixtureAuthority.treeHash(files), files, List.of());
            List<DeltaPatch> deltas = new ArrayList<>();
            for (int b = 0; b < 2; b++) {
                bases[b] = tree(root.resolve("B" + (b + 1)), "base-" + b);
                versions[b] = new InstalledVersion(b + 1, (b == 0 ? "a" : "b").repeat(40), FixtureAuthority.treeHash(FixtureAuthority.managed(bases[b])));
                Path patch = root.resolve("CashPrediction.from-" + (b + 1) + ".cpdelta");
                TreeDeltaEngine.create(bases[b], versions[b], target, initial, patch);
                byte[] content = Files.readAllBytes(patch); patchBytes.add(content);
                deltas.add(new DeltaPatch(b + 1, versions[b].commitSha(), versions[b].treeSha256(), patch.getFileName().toString(), content.length, FixtureAuthority.sha(content)));
            }
            manifest = new UpdateManifest(3, initial.commitSha(), initial.version(), initial.publishedAtUtc(), initial.assetName(),
                    initial.sizeBytes(), initial.sha256(), initial.treeSha256(), files, deltas);
            server = new LocalUpdateServer();
            client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofMillis(300)).build();
            for (int b = 0; b < 2; b++) server.put("/" + deltas.get(b).assetName(), new LocalUpdateServer.Reply(200, patchBytes.get(b), null));
            server.put("/update.json", new LocalUpdateServer.Reply(200, UpdateCodec.write(manifest).getBytes(StandardCharsets.UTF_8), null));
            server.put("/CashPrediction-portable.zip", new LocalUpdateServer.Reply(200, full, null));
        }

        private static Path tree(Path root, String content) throws Exception {
            Files.createDirectories(root.resolve("app")); Files.createDirectories(root.resolve("runtime")); Files.createDirectories(root.resolve("CashMemory"));
            for (String exe : List.of("CashPrediction.exe", "CashPrediction-Swing.exe", "CashPrediction-Web.exe")) Files.writeString(root.resolve(exe), "launcher");
            Files.writeString(root.resolve("app/данные 测试.txt"), content); Files.writeString(root.resolve("runtime/runtime.txt"), "runtime");
            Files.writeString(root.resolve("CashMemory/user.md"), "user"); Files.writeString(root.resolve("notes.txt"), "unmanaged"); return root;
        }

        Path ready(int base) { return bases[base].resolve("CashMemory/Updates/Ready"); }
        UpdatePreparer preparer(int base) throws Exception { return preparer(base, server.manifestUri(), Duration.ofSeconds(2)); }
        UpdatePreparer preparer(int base, URI uri, Duration timeout) throws Exception {
            // Используем существующий test-only seam; production constructor и CLI не получают override.
            var constructor = UpdatePreparer.class.getDeclaredConstructor(Path.class, InstalledVersion.class, URI.class,
                    HttpClient.class, Duration.class, Duration.class, Duration.class);
            if (!constructor.trySetAccessible()) throw new IllegalStateException("MAIN_MUST_OPEN_TEST_SEAM");
            return constructor.newInstance(bases[base], versions[base], uri, client, timeout, Duration.ZERO, Duration.ZERO);
        }

        /** Закрывает только собственные HTTP-объекты и ограниченные worker threads. */
        @Override public void close() { client.shutdownNow(); server.close(); }
    }
}
