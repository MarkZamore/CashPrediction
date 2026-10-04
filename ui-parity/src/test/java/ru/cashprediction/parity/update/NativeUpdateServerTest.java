package ru.cashprediction.parity.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.update.model.DeltaPatch;
import ru.cashprediction.core.update.model.InstalledVersion;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки реального loopback HTTP с файловыми ZIP/дельтами; native/GUI здесь не запускаются. */
final class NativeUpdateServerTest {
    @TempDir Path temp;
    private Path artifacts;
    private UpdateManifest manifest;

    @BeforeEach
    void fixture() throws Exception {
        artifacts = Files.createDirectory(temp.resolve("frozen"));
        Path target = dataTree(temp.resolve("target"), "target-data\n".repeat(4096));
        var inventory = FixtureAuthority.managed(target);
        Path full = artifacts.resolve("CashPrediction-portable.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(full))) {
            for (var item : inventory) {
                out.putNextEntry(new ZipEntry("CashPrediction/" + item.path()));
                Files.copy(target.resolve(item.path()), out); out.closeEntry();
            }
        }
        UpdateManifest initial = new UpdateManifest(3, "c".repeat(40), "fixture", Instant.parse("2026-10-03T00:00:00Z"),
                full.getFileName().toString(), Files.size(full), FixtureAuthority.sha(full),
                FixtureAuthority.treeHash(inventory), inventory, List.of());
        var deltas = new ArrayList<DeltaPatch>();
        for (int i = 1; i <= 2; i++) {
            Path base = dataTree(temp.resolve("base" + i), "base-" + i);
            InstalledVersion version = new InstalledVersion(i, (i == 1 ? "a" : "b").repeat(40),
                    FixtureAuthority.treeHash(FixtureAuthority.managed(base)));
            Path delta = artifacts.resolve("CashPrediction.from-" + i + ".cpdelta");
            TreeDeltaEngine.create(base, version, target, initial, delta);
            deltas.add(new DeltaPatch(i, version.commitSha(), version.treeSha256(), delta.getFileName().toString(),
                    Files.size(delta), FixtureAuthority.sha(delta)));
        }
        manifest = new UpdateManifest(initial.releaseNumber(), initial.commitSha(), initial.version(), initial.publishedAtUtc(),
                initial.assetName(), initial.sizeBytes(), initial.sha256(), initial.treeSha256(), inventory, deltas);
        Files.writeString(artifacts.resolve("update.json"), UpdateCodec.write(manifest), StandardCharsets.UTF_8);
    }

    @Test
    void servesExactFrozenBytesWithDurableCountersAndTrace() throws Exception {
        Path owned = owned();
        var config = config(owned, NativeUpdateServer.Mode.VALID, 0, 17);
        List<Path> files;
        try (var paths = Files.list(artifacts)) { files = paths.sorted().toList(); }
        var before = files.stream().map(p -> assertDoesNotThrow(() -> FixtureAuthority.sha(p))).toList();
        NativeUpdateServer server = new NativeUpdateServer(config);
        URI endpoint = server.manifestUri();
        try (server; HttpClient http = client()) {
            assertEquals("127.0.0.1", endpoint.getHost()); assertTrue(endpoint.getPort() > 0);
            for (Path file : files) {
                URI uri = endpoint.resolve(file.getFileName().toString());
                if (file.getFileName().toString().equals("update.json")) uri = URI.create(uri + "?cache=" + UUID.randomUUID());
                var response = get(http, uri);
                assertEquals(200, response.statusCode());
                assertArrayEquals(Files.readAllBytes(file), response.body());
                assertEquals(1, server.count("/" + file.getFileName()));
            }
            await(() -> server.trace().size() == files.size());
            long id = 0;
            for (var trace : server.trace()) {
                assertTrue(trace.id() > id); id = trace.id();
                assertTrue(trace.startedNanos() >= 0); assertTrue(trace.finishedNanos() >= trace.startedNanos());
                assertDoesNotThrow(() -> Instant.parse(trace.startedUtc()));
                assertDoesNotThrow(() -> Instant.parse(trace.finishedUtc()));
                assertEquals(200, trace.status()); assertEquals("COMPLETE", trace.outcome());
                assertEquals(Files.size(artifacts.resolve(trace.path().substring(1))), trace.bytes());
            }
        }
        assertEquals(before, files.stream().map(p -> assertDoesNotThrow(() -> FixtureAuthority.sha(p))).toList());
        var stats = read(owned.resolve("server-stats.json"));
        assertEquals(Boolean.TRUE, stats.get("closed")); assertEquals(0L, stats.get("active"));
        assertEquals((long) files.size(), stats.get("requests")); assertEquals(stats.get("requests"), stats.get("completed"));
        assertEquals(files.stream().mapToLong(p -> assertDoesNotThrow(() -> Files.size(p))).sum(), stats.get("bytes"));
        var receipt = read(owned.resolve("server-receipt.json"));
        assertEquals(endpoint.toASCIIString(), receipt.get("manifestUri"));
        assertEquals(owned.resolve("server.stop").toString(), receipt.get("stopPath"));
        List<String> journal = Files.readAllLines(owned.resolve("server-trace.jsonl"));
        assertEquals(files.size() * 2, journal.size());
        for (String row : journal) assertDoesNotThrow(() -> UpdateCodec.parse(row));
        assertEquals(0, server.activeRequests()); assertNoThreads(owned);
        try (HttpClient http = client()) { assertThrows(IOException.class, () -> get(http, endpoint)); }
        server.close();
    }

    @Test
    void corruptionChangesOnlySelectedOutgoingContainer() throws Exception {
        for (var mode : List.of(NativeUpdateServer.Mode.CORRUPTDELTA, NativeUpdateServer.Mode.CORRUPTFULL)) {
            try (var server = new NativeUpdateServer(config(owned(), mode, 0, 7)); HttpClient http = client()) {
                assertArrayEquals(Files.readAllBytes(artifacts.resolve("update.json")), get(http, server.manifestUri()).body());
                for (String name : List.of(manifest.assetName(), manifest.deltaPatches().get(0).assetName(), manifest.deltaPatches().get(1).assetName())) {
                    byte[] expected = Files.readAllBytes(artifacts.resolve(name));
                    boolean changed = mode == NativeUpdateServer.Mode.CORRUPTFULL ? name.endsWith(".zip") : name.endsWith(".cpdelta");
                    byte[] original = expected.clone(); if (changed) expected[0] ^= 1;
                    var response = get(http, server.manifestUri().resolve(name));
                    assertEquals(200, response.statusCode()); assertArrayEquals(expected, response.body());
                    assertArrayEquals(original, Files.readAllBytes(artifacts.resolve(name)));
                    assertEquals(1, server.count("/" + name));
                }
            }
        }
    }

    @Test
    void malformedManifestStillRequiresValidFrozenInput() throws Exception {
        try (var server = new NativeUpdateServer(config(owned(), NativeUpdateServer.Mode.MALFORMEDMANIFEST, 0, 32)); HttpClient http = client()) {
            var response = get(http, server.manifestUri());
            assertEquals(200, response.statusCode()); assertArrayEquals(new byte[] { '{' }, response.body());
            assertThrows(IOException.class, () -> UpdateCodec.read(new String(response.body(), StandardCharsets.UTF_8)));
            assertEquals(1, server.count("/update.json"));
        }
        Files.writeString(artifacts.resolve("update.json"), "{");
        assertThrows(IOException.class, () -> new NativeUpdateServer(config(owned(), NativeUpdateServer.Mode.MALFORMEDMANIFEST, 0, 32)));
    }

    @Test
    void exactlyFirstTwoManifestRequestsFailIncludingConcurrentRequests() throws Exception {
        try (var server = new NativeUpdateServer(config(owned(), NativeUpdateServer.Mode.FIRST_TWO_503, 0, 32)); HttpClient http = client()) {
            var futures = new ArrayList<CompletableFuture<HttpResponse<byte[]>>>();
            for (int i = 0; i < 3; i++) futures.add(http.sendAsync(request(URI.create(server.manifestUri() + "?cache=" + UUID.randomUUID())),
                    HttpResponse.BodyHandlers.ofByteArray()));
            var replies = new ArrayList<HttpResponse<byte[]>>();
            for (var future : futures) replies.add(future.get(5, TimeUnit.SECONDS));
            assertEquals(2, replies.stream().filter(r -> r.statusCode() == 503).count());
            assertEquals(1, replies.stream().filter(r -> r.statusCode() == 200).count());
            assertEquals(200, get(http, server.manifestUri().resolve(manifest.assetName())).statusCode());
            await(() -> server.trace().size() == 4);
            assertEquals(3, server.count("/update.json")); assertEquals(1, server.count("/" + manifest.assetName()));
        }
    }

    @Test
    void offlineClosesConnectionsAndRecordsEveryHttpClientRetry() throws Exception {
        try (var server = new NativeUpdateServer(config(owned(), NativeUpdateServer.Mode.OFFLINE_CLOSE, 0, 32)); HttpClient http = client()) {
            assertThrows(IOException.class, () -> get(http, server.manifestUri()));
            await(() -> server.activeRequests() == 0 && !server.trace().isEmpty());
            assertEquals(server.trace().size(), server.count("/update.json"));
            for (var trace : server.trace()) { assertEquals(0, trace.status()); assertEquals(0, trace.bytes()); }
        }
    }

    @Test
    void delayedHeadersCanBeCancelledBeforeAnyBytes() throws Exception {
        Path owned = owned();
        NativeUpdateServer server = new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.DELAYEDHEADERS, 60_000, 32));
        try (server; HttpClient http = client()) {
            var future = http.sendAsync(request(server.manifestUri()), HttpResponse.BodyHandlers.ofByteArray());
            await(() -> server.count("/update.json") == 1 && server.activeRequests() == 1);
            assertFalse(future.isDone()); server.cancelTransfers();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            await(() -> server.activeRequests() == 0 && !server.trace().isEmpty());
            assertTrue(server.trace().stream().allMatch(t -> t.bytes() == 0 && t.status() == 0));
        }
        assertNoThreads(owned);
    }

    @Test
    void slowChunksPermitPartialReadAndCleanClose() throws Exception {
        Path owned = owned();
        NativeUpdateServer server = new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.SLOWCHUNKS, 60_000, 8));
        try (server; HttpClient http = client()) {
            var response = http.send(request(server.manifestUri().resolve(manifest.assetName())), HttpResponse.BodyHandlers.ofInputStream());
            try (var input = response.body()) {
                assertEquals(200, response.statusCode());
                assertArrayEquals(java.util.Arrays.copyOf(Files.readAllBytes(artifacts.resolve(manifest.assetName())), 8), input.readNBytes(8));
                server.close();
            }
            var trace = server.trace().getFirst(); assertEquals(8, trace.bytes()); assertEquals(200, trace.status());
            assertEquals("CANCELLED", trace.outcome()); assertEquals(0, server.activeRequests());
        }
        assertNoThreads(owned);
    }

    @Test
    void pauseReleaseAndPinnedStopFileControlLifetime() throws Exception {
        Path owned = owned();
        try (var server = new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.VALID, 0, 32)); HttpClient http = client()) {
            server.pauseTransfers();
            var response = http.sendAsync(request(server.manifestUri()), HttpResponse.BodyHandlers.ofByteArray());
            await(() -> server.activeRequests() == 1 && server.count("/update.json") == 1);
            assertFalse(response.isDone()); server.releaseTransfers();
            assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
            Files.createFile(server.stopPath());
            await(() -> assertDoesNotThrow(() -> read(owned.resolve("server-stats.json"))).get("closed").equals(Boolean.TRUE));
        }
        assertNoThreads(owned);
    }

    @Test
    void concurrentFinishesAndPinnedStopPublishStatsDespiteOldFixedTemp() throws Exception {
        Path owned = owned();
        try (var server = new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.VALID, 0, 32)); HttpClient http = client()) {
            // Воспроизводим блокирующий остаток прежней публикации без подмены ошибок или timeout.
            Path oldTemporary = owned.resolve("server-stats.json.tmp");
            Files.writeString(oldTemporary, "previous-publication");
            server.pauseTransfers();
            var responses = new ArrayList<CompletableFuture<HttpResponse<byte[]>>>();
            for (int i = 0; i < 8; i++) responses.add(http.sendAsync(request(server.manifestUri()), HttpResponse.BodyHandlers.ofByteArray()));
            await(() -> server.count("/update.json") >= 1);
            server.releaseTransfers();
            for (var response : responses) assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
            // Последние FINISH и callback остановки конкурируют за окончательную квитанцию.
            Files.createFile(server.stopPath());
            await(() -> assertDoesNotThrow(() -> read(owned.resolve("server-stats.json"))).get("closed").equals(Boolean.TRUE));
            server.requireHealthy();
            var stats = read(owned.resolve("server-stats.json"));
            assertEquals(8L, stats.get("requests")); assertEquals(8L, stats.get("completed"));
            assertEquals(0L, stats.get("active")); assertEquals(Boolean.TRUE, stats.get("healthy"));
            assertEquals(8, server.trace().size());
            assertEquals("previous-publication", Files.readString(oldTemporary));
            try (var entries = Files.list(owned)) {
                assertEquals(List.of(oldTemporary), entries.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList());
            }
        }
        assertNoThreads(owned);
    }

    @Test
    void windowsReaderClosesBeforeBoundedPublicationRetrySucceeds() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"), "WINDOWS_SHARING_ONLY");
        Path owned = owned();
        try (var server = new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.VALID, 0, 32)); HttpClient http = client()) {
            CompletableFuture<HttpResponse<byte[]>> response;
            // JDK option воспроизводит reader без FILE_SHARE_DELETE; handle обязательно закрыт до get/close.
            java.nio.file.OpenOption noShareDelete = (java.nio.file.OpenOption) Class.forName("com.sun.nio.file.ExtendedOpenOption")
                    .getField("NOSHARE_DELETE").get(null);
            try (var reader = java.nio.channels.FileChannel.open(owned.resolve("server-stats.json"),
                    java.util.Set.of(java.nio.file.StandardOpenOption.READ, noShareDelete))) {
                assertTrue(reader.isOpen());
                response = http.sendAsync(request(server.manifestUri()), HttpResponse.BodyHandlers.ofByteArray());
                await(() -> server.activeRequests() == 1);
                Thread.sleep(100);
                assertFalse(response.isDone());
            }
            assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());
            Files.createFile(server.stopPath());
            await(() -> assertDoesNotThrow(() -> read(owned.resolve("server-stats.json"))).get("closed").equals(Boolean.TRUE));
            server.requireHealthy();
            assertEquals(1L, read(owned.resolve("server-stats.json")).get("completed"));
            try (var entries = Files.list(owned)) {
                assertTrue(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
            }
        }
        assertNoThreads(owned);
    }

    @Test
    void pinnedCancelFileAbortsPausedTransfer() throws Exception {
        Path owned = owned();
        try (var server = new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.VALID, 0, 32)); HttpClient http = client()) {
            server.pauseTransfers();
            var future = http.sendAsync(request(server.manifestUri()), HttpResponse.BodyHandlers.ofByteArray());
            await(() -> server.activeRequests() == 1);
            Files.createFile(owned.resolve("server.cancel"));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            await(() -> server.activeRequests() == 0);
        }
        assertEquals(Boolean.TRUE, read(owned.resolve("server-stats.json")).get("cancelled"));
    }

    @Test
    void rejectsUnknownRoutesEncodedPathsQueriesAndMethods() throws Exception {
        try (var server = new NativeUpdateServer(config(owned(), NativeUpdateServer.Mode.VALID, 0, 32)); HttpClient http = client()) {
            for (String relative : List.of("other", "%75pdate.json", "update.json?bad=true", "CashPrediction-portable.zip?cache=" + UUID.randomUUID())) {
                assertEquals(404, get(http, server.manifestUri().resolve(relative)).statusCode());
            }
            assertEquals(1, server.count("/other")); assertEquals(1, server.count("/%75pdate.json"));
            var response = http.send(HttpRequest.newBuilder(server.manifestUri()).timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, response.statusCode());
        }
    }

    @Test
    void validatesDigestBeforeWritingReceiptsAndDetectsLaterMutation() throws Exception {
        Path full = artifacts.resolve(manifest.assetName()); byte[] original = Files.readAllBytes(full);
        Path owned = owned(); Files.write(full, new byte[] { 1 });
        assertThrows(IOException.class, () -> new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.VALID, 0, 32)));
        try (var entries = Files.list(owned)) { assertEquals(0, entries.count()); }
        Files.write(full, original);
        try (var server = new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.VALID, 0, 32)); HttpClient http = client()) {
            original[0] ^= 1; Files.write(full, original);
            assertEquals(409, get(http, server.manifestUri().resolve(manifest.assetName())).statusCode());
        }
    }

    @Test
    void configIsStrictAsciiBase64Utf8JsonAndLimitsAreBounded() throws Exception {
        Path owned = owned();
        Path unicode = temp.resolve("fixture-\u041c\u043e\u0438-\u0394"); Files.move(artifacts, unicode);
        String json = JsonWriter.write(Map.of("artifactDir", unicode.toString(), "ownedTempUuid", owned.toString(),
                "mode", "valid", "delayMillis", 0, "chunkBytes", 4096, "maxLifetimeMillis", 60_000));
        String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertTrue(encoded.chars().allMatch(c -> c < 128));
        var parsed = NativeUpdateServer.Config.fromBase64(encoded); assertEquals(unicode, parsed.artifactDir());
        try (var server = new NativeUpdateServer(parsed); HttpClient http = client()) { assertEquals(200, get(http, server.manifestUri()).statusCode()); }
        for (String bad : List.of("{}", json.replace("\"valid\"", "\"unknown\""), json.replace("4096", "0"),
                json.replace("60000", "999999"), json.replace("{", "{\"mode\":\"valid\","))) {
            String argument = Base64.getEncoder().encodeToString(bad.getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> NativeUpdateServer.Config.fromBase64(argument));
        }
        assertThrows(IOException.class, () -> NativeUpdateServer.Config.fromBase64("\u041c"));
        assertThrows(IOException.class, () -> NativeUpdateServer.Config.fromBase64("/w=="));
    }

    @Test
    void rejectsNonEmptyNonUuidTraversalAndMissingAssets() throws Exception {
        Path occupied = owned(); Files.createFile(occupied.resolve("existing"));
        assertThrows(IOException.class, () -> new NativeUpdateServer(config(occupied, NativeUpdateServer.Mode.VALID, 0, 32)));
        assertThrows(IOException.class, () -> new NativeUpdateServer(config(temp, NativeUpdateServer.Mode.VALID, 0, 32)));
        Path owned = owned();
        assertThrows(IOException.class, () -> new NativeUpdateServer(new NativeUpdateServer.Config(
                artifacts.resolve(".." ).resolve("frozen"), owned, NativeUpdateServer.Mode.VALID, 0, 32, 60_000)));
        Path delta = artifacts.resolve(manifest.deltaPatches().getFirst().assetName()); Files.delete(delta);
        assertThrows(IOException.class, () -> new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.VALID, 0, 32)));
    }

    @Test
    void rejectsPhysicalAliasesEvenWhenDescriptorsMatchTheirBytes() throws Exception {
        DeltaPatch first = manifest.deltaPatches().getFirst();
        Path delta = artifacts.resolve(first.assetName()); Files.delete(delta);
        try { Files.createLink(delta, artifacts.resolve(manifest.assetName())); }
        catch (IOException | UnsupportedOperationException ex) { org.junit.jupiter.api.Assumptions.assumeTrue(false, "HARDLINK_UNAVAILABLE"); }
        DeltaPatch alias = new DeltaPatch(first.baseReleaseNumber(), first.baseCommitSha(), first.baseTreeSha256(),
                first.assetName(), manifest.sizeBytes(), manifest.sha256());
        UpdateManifest changed = new UpdateManifest(manifest.releaseNumber(), manifest.commitSha(), manifest.version(),
                manifest.publishedAtUtc(), manifest.assetName(), manifest.sizeBytes(), manifest.sha256(), manifest.treeSha256(),
                manifest.files(), List.of(alias, manifest.deltaPatches().get(1)));
        Files.writeString(artifacts.resolve("update.json"), UpdateCodec.write(changed));
        IOException rejected = assertThrows(IOException.class, () -> new NativeUpdateServer(config(owned(), NativeUpdateServer.Mode.VALID, 0, 32)));
        assertEquals("FIXTURE_FILE_ALIAS", rejected.getMessage());
    }

    @Test
    void rejectsDuplicateManifestKeysAndIncorrectTreeDigest() throws Exception {
        Path json = artifacts.resolve("update.json");
        String original = Files.readString(json);
        Files.writeString(json, original.replaceFirst("\\{", "{\"schemaVersion\":2,"));
        assertThrows(IOException.class, () -> new NativeUpdateServer(config(owned(), NativeUpdateServer.Mode.VALID, 0, 32)));
        Files.writeString(json, original.replace(manifest.treeSha256(), "0".repeat(64)));
        IOException rejected = assertThrows(IOException.class, () -> new NativeUpdateServer(config(owned(), NativeUpdateServer.Mode.VALID, 0, 32)));
        assertEquals("FIXTURE_TREE_DIGEST", rejected.getMessage());
    }

    @Test
    void delayedHeadersProduceRealHttpClientTimeout() throws Exception {
        Path owned = owned();
        try (var server = new NativeUpdateServer(config(owned, NativeUpdateServer.Mode.DELAYEDHEADERS, 60_000, 32)); HttpClient http = client()) {
            HttpRequest request = HttpRequest.newBuilder(server.manifestUri()).timeout(Duration.ofMillis(200)).GET().build();
            assertThrows(java.net.http.HttpTimeoutException.class, () -> http.send(request, HttpResponse.BodyHandlers.ofByteArray()));
            server.cancelTransfers();
            await(() -> server.activeRequests() == 0 && !server.trace().isEmpty());
            assertTrue(server.trace().stream().allMatch(t -> t.bytes() == 0 && t.status() == 0));
        }
        assertNoThreads(owned);
    }

    @Test
    void rejectsSymbolicLinksWhenPlatformAllowsTheirCreation() throws Exception {
        Path alias = artifacts.resolve("alias");
        try { Files.createSymbolicLink(alias, artifacts.resolve("update.json")); }
        catch (IOException | UnsupportedOperationException ex) { org.junit.jupiter.api.Assumptions.assumeTrue(false, "SYMLINK_UNAVAILABLE"); }
        assertThrows(IOException.class, () -> new NativeUpdateServer(config(owned(), NativeUpdateServer.Mode.VALID, 0, 32)));
    }

    @Test
    void maximumLifetimeClosesServerWithoutExternalSignal() throws Exception {
        Path owned = owned();
        try (var server = new NativeUpdateServer(new NativeUpdateServer.Config(artifacts, owned, NativeUpdateServer.Mode.VALID, 0, 32, 1000))) {
            await(() -> assertDoesNotThrow(() -> read(owned.resolve("server-stats.json"))).get("closed").equals(Boolean.TRUE));
        }
        assertNoThreads(owned);
    }

    private Path owned() throws IOException { return Files.createDirectory(temp.resolve(UUID.randomUUID().toString())); }
    private NativeUpdateServer.Config config(Path owned, NativeUpdateServer.Mode mode, int delay, int chunk) {
        return new NativeUpdateServer.Config(artifacts, owned, mode, delay, chunk, 60_000);
    }
    private static Path dataTree(Path root, String value) throws IOException {
        // Только обычный payload: фикстура не подделывает launcher, helper или runtime.
        Files.createDirectories(root.resolve("app")); Files.writeString(root.resolve("app/data.txt"), value); return root;
    }
    private static HttpClient client() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build(); }
    private static HttpRequest request(URI uri) { return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build(); }
    private static HttpResponse<byte[]> get(HttpClient client, URI uri) throws IOException, InterruptedException {
        return client.send(request(uri), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static Map<String, Object> read(Path file) throws IOException {
        // Reader не переживает callback polling и не удерживает Windows target во время следующего move.
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IOException("TEST_RECEIPT_LIMIT");
            return UpdateCodec.object(UpdateCodec.parse(new String(bytes, StandardCharsets.UTF_8)));
        }
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "CONDITION_TIMEOUT");
    }
    private static void assertNoThreads(Path owned) {
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().contains(owned.getFileName().toString())), "SERVER_THREADS_ALIVE");
    }
}
