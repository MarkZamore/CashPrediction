package ru.cashprediction.core.update.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.model.DeltaPatch;
import ru.cashprediction.core.update.model.InstalledVersion;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;
import static org.junit.jupiter.api.Assertions.*;

/** Локальные HTTP-сценарии настоящих загрузок, блокировок и отмены подготовки. */
class UpdatePreparerTest {
    @TempDir Path temporary;
    private static final String COMMIT_A = "a".repeat(40);
    private static final String COMMIT_B = "b".repeat(40);
    private static final String COMMIT_C = "c".repeat(40);

    @Test
    void fullTreeIsVerifiedAndRepeatedCallsNeverPoll() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            Path user = fixture.installation.resolve("CashMemory/user.txt");
            Files.createDirectories(user.getParent());
            Files.writeString(user, "user-data");
            try (UpdatePreparer preparer = fixture.preparer()) {
                assertTrue(preparer.prepare());
                assertTrue(preparer.prepare());
                Thread.sleep(300);
                assertTrue(preparer.prepare());
                assertEquals(1, fixture.manifestRequests.get());
                assertEquals(1, fixture.fullRequests.get());
                TreeDeltaEngine.verify(fixture.ready().resolve("tree"), fixture.target.files(), fixture.target.treeSha256());
                assertEquals(fixture.target, UpdateCodec.read(Files.readString(fixture.ready().resolve("update.json"))));
                assertEquals("user-data", Files.readString(user));
                assertFalse(Files.exists(fixture.updates().resolve("payload.download")));
                assertFalse(Files.exists(fixture.updates().resolve("Staging")));
            }
        }
    }

    @Test
    void malformedSchemaRetriesExactlyThreeTimesWithFreshQueriesAndBackoff() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.wireManifest = "{\"schemaVersion\":99}".getBytes(StandardCharsets.UTF_8);
            try (UpdatePreparer preparer = fixture.preparer(Duration.ofSeconds(2), Duration.ofMillis(30), Duration.ofMillis(60))) {
                long began = System.nanoTime();
                assertFalse(preparer.prepare());
                assertTrue(System.nanoTime() - began >= Duration.ofMillis(90).toNanos());
                assertFalse(preparer.prepare());
                Thread.sleep(100);
                assertEquals(3, fixture.manifestRequests.get());
                assertEquals(3, fixture.queries.size());
                assertEquals(0, fixture.fullRequests.get());
                assertFalse(Files.exists(fixture.ready()));
            }
        }
    }

    @Test
    void transientManifestFailureCanSucceedOnThirdAttempt() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.failManifestUntil = 2;
            try (UpdatePreparer preparer = fixture.preparer()) {
                assertTrue(preparer.prepare());
                assertEquals(3, fixture.manifestRequests.get());
                assertEquals(1, fixture.fullRequests.get());
            }
        }
    }

    @Test
    void slowBodyIsWithinDeadlineIncludingAfterHeaders() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.slowManifest = true;
            try (UpdatePreparer preparer = fixture.preparer(Duration.ofMillis(500), Duration.ZERO, Duration.ZERO)) {
                assertTimeoutPreemptively(Duration.ofSeconds(4), () -> assertFalse(preparer.prepare()));
                assertEquals(3, fixture.manifestRequests.get());
                assertEquals(0, fixture.fullRequests.get());
                assertFalse(Files.exists(fixture.ready()));
            }
        }
    }

    @Test
    void sameCopyHasOnlyOneConcurrentPreparationWhileDifferentCopyCanProceed() throws Exception {
        try (Fixture fixture = new Fixture(temporary);
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.slowFull = true;
            try (UpdatePreparer first = fixture.preparer(); UpdatePreparer second = fixture.preparer()) {
                var firstResult = tasks.submit(first::prepare);
                assertTrue(fixture.bodyStarted.await(3, TimeUnit.SECONDS));
                assertFalse(second.prepare());
                assertEquals(1, fixture.manifestRequests.get());
                assertEquals(1, fixture.fullRequests.get());
                Path otherRoot = temporary.resolve("other-copy");
                makeTree(otherRoot, "base-a");
                try (UpdatePreparer other = fixture.preparer(otherRoot, fixture.current, Duration.ofSeconds(3), Duration.ZERO, Duration.ZERO)) {
                    var otherResult = tasks.submit(other::prepare);
                    assertTrue(fixture.secondBodyStarted.await(3, TimeUnit.SECONDS));
                    fixture.releaseBody.countDown();
                    assertTrue(firstResult.get(3, TimeUnit.SECONDS));
                    assertTrue(otherResult.get(3, TimeUnit.SECONDS));
                    assertEquals(2, fixture.manifestRequests.get());
                    assertEquals(2, fixture.fullRequests.get());
                }
            }
        }
    }

    @Test
    void closeDuringActualSlowStreamCleansAndNextSessionDownloadsFromStart() throws Exception {
        try (Fixture fixture = new Fixture(temporary);
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.slowFull = true;
            try (UpdatePreparer first = fixture.preparer()) {
                var firstResult = tasks.submit(first::prepare);
                assertTrue(fixture.bodyStarted.await(3, TimeUnit.SECONDS));
                awaitPartialDownload(fixture.updates().resolve("payload.download"));
                assertTimeoutPreemptively(Duration.ofSeconds(3), first::close);
                assertFalse(firstResult.get(3, TimeUnit.SECONDS));
                assertFalse(Files.exists(fixture.ready()));
                assertFalse(Files.exists(fixture.updates().resolve("payload.download")));
                assertFalse(Files.exists(fixture.updates().resolve("Staging")));
                assertEquals(0, fixture.deltaRequests.get());
                fixture.slowFull = false;
                fixture.releaseBody.countDown();
                try (UpdatePreparer next = fixture.preparer()) {
                    assertTrue(next.prepare());
                    assertEquals(2, fixture.fullRequests.get());
                    assertEquals(0, fixture.rangeRequests.get());
                }
            }
        }
    }

    @Test
    void closeDuringDeltaNeverStartsFullFallback() throws Exception {
        try (Fixture fixture = new Fixture(temporary);
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.publishDelta(fixture.installation, fixture.current, "CashPrediction.cpdelta");
            fixture.slowDelta = true;
            try (UpdatePreparer preparer = fixture.preparer()) {
                var result = tasks.submit(preparer::prepare);
                assertTrue(fixture.bodyStarted.await(3, TimeUnit.SECONDS));
                awaitPartialDownload(fixture.updates().resolve("payload.download"));
                preparer.close();
                assertFalse(result.get(3, TimeUnit.SECONDS));
                assertEquals(1, fixture.deltaRequests.get());
                assertEquals(0, fixture.fullRequests.get());
                assertFalse(Files.exists(fixture.ready()));
                assertFalse(Files.exists(fixture.updates().resolve("payload.download")));
            }
        }
    }

    @Test
    void corruptDeltaFallsBackToVerifiedFullExactlyOnce() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.publishDelta(fixture.installation, fixture.current, "CashPrediction.cpdelta");
            fixture.deltaBytes = new byte[] {1, 2, 3};
            try (UpdatePreparer preparer = fixture.preparer()) {
                assertTrue(preparer.prepare());
                assertEquals(1, fixture.deltaRequests.get());
                assertEquals(1, fixture.fullRequests.get());
                TreeDeltaEngine.verify(fixture.ready().resolve("tree"), fixture.target.files(), fixture.target.treeSha256());
            }
        }
    }

    @Test
    void matchingButInvalidDeltaContainerFallsBackAfterHashValidation() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.deltaBytes = "invalid-container".getBytes(StandardCharsets.UTF_8);
            DeltaPatch patch = new DeltaPatch(1, COMMIT_A, fixture.current.treeSha256(),
                    "CashPrediction.cpdelta", fixture.deltaBytes.length, hash(fixture.deltaBytes));
            fixture.setPatches(List.of(patch));
            try (UpdatePreparer preparer = fixture.preparer()) {
                assertTrue(preparer.prepare());
                assertEquals(1, fixture.deltaRequests.get());
                assertEquals(1, fixture.fullRequests.get());
            }
        }
    }

    @Test
    void bothDirectBasesSelectByReleaseCommitAndActualTree() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            Path baseB = temporary.resolve("base-b");
            makeTree(baseB, "base-b");
            InstalledVersion versionB = version(baseB, 2, COMMIT_B);
            DeltaPatch patchA = fixture.createDelta(fixture.installation, fixture.current, "CashPrediction.cpdelta");
            byte[] bytesA = fixture.deltaBytes;
            DeltaPatch patchB = fixture.createDelta(baseB, versionB, "CashPrediction.from-2.cpdelta");
            byte[] bytesB = fixture.deltaBytes;
            fixture.setPatches(List.of(patchA, patchB));
            fixture.deltaBytes = bytesA;
            try (UpdatePreparer a = fixture.preparer()) { assertTrue(a.prepare()); }
            fixture.deltaBytes = bytesB;
            try (UpdatePreparer b = fixture.preparer(baseB, versionB, Duration.ofSeconds(3), Duration.ZERO, Duration.ZERO)) {
                assertTrue(b.prepare());
            }
            assertEquals(2, fixture.deltaRequests.get());
            assertEquals(0, fixture.fullRequests.get());
            TreeDeltaEngine.verify(fixture.installation, TreeDeltaEngine.inventory(fixture.installation), fixture.current.treeSha256());
            TreeDeltaEngine.verify(baseB, TreeDeltaEngine.inventory(baseB), versionB.treeSha256());
            assertFalse(Files.exists(fixture.updates().resolve("DeltaBase")));
            assertFalse(Files.exists(baseB.resolve("CashMemory/Updates/DeltaBase")));
            TreeDeltaEngine.verify(baseB.resolve("CashMemory/Updates/Ready/tree"), fixture.target.files(), fixture.target.treeSha256());
            assertEquals(Set.of("/CashPrediction.cpdelta", "/CashPrediction.from-2.cpdelta"), fixture.deltaPaths);
        }
    }

    @Test
    void directDeltaUsesCheckedSnapshotPreservesReadOnlyBaseAndCleansIt() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            Path readOnly = fixture.installation.resolve("runtime/jvm.txt");
            var dos = Files.getFileAttributeView(readOnly, java.nio.file.attribute.DosFileAttributeView.class);
            if (dos != null) dos.setReadOnly(true);
            else {
                var permissions = new java.util.HashSet<>(Files.getPosixFilePermissions(readOnly));
                permissions.removeAll(Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE));
                Files.setPosixFilePermissions(readOnly, permissions);
            }
            InstalledVersion installed = version(fixture.installation, 1, COMMIT_A);
            var baseFiles = TreeDeltaEngine.inventory(fixture.installation);
            fixture.publishDelta(fixture.installation, installed, "CashPrediction.cpdelta");
            try (UpdatePreparer preparer = fixture.preparer(fixture.installation, installed,
                    Duration.ofSeconds(3), Duration.ZERO, Duration.ZERO)) {
                assertTrue(preparer.prepare());
            }
            assertEquals(1, fixture.deltaRequests.get());
            assertEquals(0, fixture.fullRequests.get());
            TreeDeltaEngine.verify(fixture.installation, baseFiles, installed.treeSha256());
            TreeDeltaEngine.verify(fixture.ready().resolve("tree"), fixture.target.files(), fixture.target.treeSha256());
            assertFalse(Files.exists(fixture.updates().resolve("DeltaBase")));
            assertFalse(Files.exists(fixture.updates().resolve("payload.download")));
        }
    }

    @Test
    void mismatchOfAnyBaseIdentitySkipsDelta() throws Exception {
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            try (Fixture fixture = new Fixture(temporary.resolve("case-" + mismatch))) {
                DeltaPatch patch = new DeltaPatch(mismatch == 0 ? 2 : 1,
                        mismatch == 1 ? COMMIT_B : COMMIT_A,
                        mismatch == 2 ? "0".repeat(64) : fixture.current.treeSha256(),
                        "CashPrediction.cpdelta", 10, "0".repeat(64));
                fixture.setPatches(List.of(patch));
                try (UpdatePreparer preparer = fixture.preparer()) { assertTrue(preparer.prepare()); }
                assertEquals(0, fixture.deltaRequests.get());
                assertEquals(1, fixture.fullRequests.get());
            }
        }
    }

    @Test
    void modifiedInstalledTreeSkipsOtherwiseMatchingDelta() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.publishDelta(fixture.installation, fixture.current, "CashPrediction.cpdelta");
            Files.writeString(fixture.installation.resolve("app/data.txt"), "local-change");
            try (UpdatePreparer preparer = fixture.preparer()) { assertTrue(preparer.prepare()); }
            assertEquals(0, fixture.deltaRequests.get());
            assertEquals(1, fixture.fullRequests.get());
            assertEquals("local-change", Files.readString(fixture.installation.resolve("app/data.txt")));
        }
    }

    @Test
    void invalidFullHashLengthOrTreeCannotBecomeReady() throws Exception {
        for (int corruption = 0; corruption < 3; corruption++) {
            try (Fixture fixture = new Fixture(temporary.resolve("corrupt-" + corruption))) {
                if (corruption == 0) fixture.fullBytes[fixture.fullBytes.length / 2] ^= 1;
                if (corruption == 1) fixture.fullBytes = new byte[] {0};
                if (corruption == 2) {
                    fixture.fullBytes = zip(fixture.targetRoot, "changed-content");
                    fixture.target = new UpdateManifest(3, COMMIT_C, "3", Instant.EPOCH,
                            "CashPrediction-portable.zip", fixture.fullBytes.length, hash(fixture.fullBytes),
                            fixture.target.treeSha256(), fixture.target.files(), List.of());
                    fixture.refreshManifest();
                }
                try (UpdatePreparer preparer = fixture.preparer()) { assertFalse(preparer.prepare()); }
                assertFalse(Files.exists(fixture.ready()));
                assertFalse(Files.exists(fixture.updates().resolve("payload.download")));
            }
        }
    }

    @Test
    void sameOrLowerReleaseDoesNotDownloadEvenWithDifferentCommit() throws Exception {
        for (int release : List.of(1, 2)) {
            try (Fixture fixture = new Fixture(temporary.resolve("release-" + release))) {
                InstalledVersion installed = new InstalledVersion(2, COMMIT_A, fixture.current.treeSha256());
                fixture.target = new UpdateManifest(release, COMMIT_C, "version", Instant.EPOCH,
                        fixture.target.assetName(), fixture.target.sizeBytes(), fixture.target.sha256(),
                        fixture.target.treeSha256(), fixture.target.files(), List.of());
                fixture.refreshManifest();
                try (UpdatePreparer preparer = fixture.preparer(fixture.installation, installed,
                        Duration.ofSeconds(3), Duration.ZERO, Duration.ZERO)) { assertFalse(preparer.prepare()); }
                assertEquals(1, fixture.manifestRequests.get());
                assertEquals(0, fixture.fullRequests.get());
            }
        }
    }

    @Test
    void developmentIdentityIsInertAndExistingInstallJournalBlocksNetwork() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            for (InstalledVersion identity : List.of(new InstalledVersion(0, COMMIT_A, fixture.current.treeSha256()),
                    new InstalledVersion(1, "abc1234", fixture.current.treeSha256()))) {
                try (UpdatePreparer preparer = fixture.preparer(fixture.installation, identity,
                        Duration.ofSeconds(2), Duration.ZERO, Duration.ZERO)) { assertFalse(preparer.prepare()); }
            }
            assertFalse(Files.exists(fixture.updates()));
            Files.createDirectories(fixture.updates());
            Files.writeString(fixture.updates().resolve("install-journal.json"), "active");
            try (UpdatePreparer preparer = fixture.preparer()) { assertFalse(preparer.prepare()); }
            assertEquals(0, fixture.manifestRequests.get());
        }
    }

    @Test
    void loopbackAndTimeoutSeamsAreNotProductionOverrides() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            assertThrows(IllegalArgumentException.class, () ->
                    new UpdatePreparer(fixture.installation, fixture.current, fixture.uri()));
            try (HttpClient redirects = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
                assertThrows(IllegalArgumentException.class, () -> new UpdatePreparer(fixture.installation,
                        fixture.current, fixture.uri(), redirects, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO));
            }
            assertEquals(0, fixture.manifestRequests.get());
        }
    }

    @Test
    void redirectsAreBoundedAndForeignHostIsNeverContacted() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.redirect = "http://localhost:" + fixture.server.getAddress().getPort() + "/forbidden";
            try (UpdatePreparer preparer = fixture.preparer()) { assertFalse(preparer.prepare()); }
            assertEquals(3, fixture.manifestRequests.get());
            assertEquals(0, fixture.forbiddenRequests.get());
            fixture.redirect = "/update.json";
            fixture.manifestRequests.set(0);
            try (UpdatePreparer preparer = fixture.preparer()) { assertFalse(preparer.prepare()); }
            assertEquals(18, fixture.manifestRequests.get());
            assertEquals(0, fixture.fullRequests.get());
        }
    }

    @Test
    void sameOriginRelativeRedirectWorksWithoutAddingPolls() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.redirect = "/manifest-target";
            try (UpdatePreparer preparer = fixture.preparer()) { assertTrue(preparer.prepare()); }
            assertEquals(1, fixture.manifestRequests.get());
            assertEquals(1, fixture.redirectTargetRequests.get());
            assertEquals(1, fixture.fullRequests.get());
        }
    }

    @Test
    void foreignAssetNameIsRejectedBeforePayloadRequest() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.wireManifest = new String(fixture.wireManifest, StandardCharsets.UTF_8)
                    .replace("CashPrediction-portable.zip", "../foreign.zip").getBytes(StandardCharsets.UTF_8);
            try (UpdatePreparer preparer = fixture.preparer()) { assertFalse(preparer.prepare()); }
            assertEquals(3, fixture.manifestRequests.get());
            assertEquals(0, fixture.fullRequests.get());
        }
    }

    @Test
    void existingReadySurvivesUnavailableManifestAndFailedNewPayload() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            try (UpdatePreparer first = fixture.preparer()) { assertTrue(first.prepare()); }
            UpdateManifest cached = fixture.target;
            fixture.wireManifest = new byte[] {(byte) 0xff};
            try (UpdatePreparer offline = fixture.preparer()) { assertTrue(offline.prepare()); }
            assertEquals(cached, UpdateCodec.read(Files.readString(fixture.ready().resolve("update.json"))));
            fixture.target = new UpdateManifest(4, "d".repeat(40), "4", Instant.EPOCH,
                    cached.assetName(), cached.sizeBytes(), cached.sha256(), cached.treeSha256(), cached.files(), List.of());
            fixture.refreshManifest();
            fixture.fullBytes[fixture.fullBytes.length / 2] ^= 1;
            try (UpdatePreparer failed = fixture.preparer()) { assertTrue(failed.prepare()); }
            assertEquals(cached, UpdateCodec.read(Files.readString(fixture.ready().resolve("update.json"))));
            TreeDeltaEngine.verify(fixture.ready().resolve("tree"), cached.files(), cached.treeSha256());
        }
    }

    @Test
    void chunkedOverflowIsRejectedWithoutReadyAndManifestSizeIsBounded() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            fixture.chunked = true;
            fixture.fullBytes = java.util.Arrays.copyOf(fixture.fullBytes, fixture.fullBytes.length + 1);
            try (UpdatePreparer preparer = fixture.preparer()) { assertFalse(preparer.prepare()); }
            assertFalse(Files.exists(fixture.ready()));
            assertFalse(Files.exists(fixture.updates().resolve("payload.download")));
            fixture.wireManifest = new byte[ReadyStore.JSON_LIMIT + 1];
            try (UpdatePreparer preparer = fixture.preparer()) { assertFalse(preparer.prepare()); }
            assertEquals(4, fixture.manifestRequests.get());
            assertEquals(1, fixture.fullRequests.get());
        }
    }

    @Test
    void redirectCredentialsFragmentMalformedHostAndPortChangeAreRejected() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            for (String redirect : List.of("http://user@127.0.0.1:" + fixture.server.getAddress().getPort() + "/forbidden",
                    "/forbidden#fragment", "http://[invalid]/forbidden", "http://127.0.0.1:1/forbidden")) {
                fixture.redirect = redirect;
                try (UpdatePreparer preparer = fixture.preparer()) { assertFalse(preparer.prepare()); }
            }
            assertEquals(12, fixture.manifestRequests.get());
            assertEquals(0, fixture.forbiddenRequests.get());
            assertEquals(0, fixture.fullRequests.get());
        }
    }

    @Test
    void productionRedirectPolicyAcceptsOnlyFrozenHttpsHosts() throws Exception {
        try (Fixture fixture = new Fixture(temporary);
             UpdatePreparer production = new UpdatePreparer(fixture.installation, fixture.current,
                     URI.create("https://github.com/MarkZamore/CashPrediction/releases/latest/download/update.json"))) {
            // Проверка trust boundary без настоящей сети: тест обращается к политике URI.
            var policy = UpdatePreparer.class.getDeclaredMethod("validateUri", URI.class);
            policy.setAccessible(true);
            for (String allowed : List.of("https://github.com/path", "https://objects.githubusercontent.com/path",
                    "https://release-assets.githubusercontent.com/path?token=test", "https://GITHUB.COM:443/path")) {
                assertDoesNotThrow(() -> policy.invoke(production, URI.create(allowed)));
            }
            for (String blocked : List.of("http://github.com/path", "https://github.com.evil.example/path",
                    "https://evil.example/path", "https://github-releases.githubusercontent.com/path",
                    "https://user@github.com/path", "https://github.com:444/path", "https://github.com/path#fragment",
                    "https:/hostless/path", "http://127.0.0.1:1234/path")) {
                var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> policy.invoke(production, URI.create(blocked)));
                assertInstanceOf(IOException.class, error.getCause());
            }
            assertEquals(0, fixture.manifestRequests.get());
        }
    }

    @Test
    void preUiRecoveryPublishesVerifiedStagingWithoutAnyHttpRequest() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            Path updates = fixture.updates();
            Path stage = updates.resolve("Staging");
            Files.createDirectories(stage);
            Path archive = temporary.resolve("recovery.zip");
            Files.write(archive, fixture.fullBytes);
            TreeDeltaEngine.extractFull(archive, fixture.target, stage.resolve("tree"));
            byte[] json = UpdateCodec.write(fixture.target).getBytes(StandardCharsets.UTF_8);
            UpdateFiles.writeAtomic(stage.resolve("update.json"), json);
            UpdateFiles.writeAtomic(updates.resolve("prepare-journal.json"), json);
            assertTrue(UpdatePreparer.recoverReady(fixture.installation, fixture.current.releaseNumber()));
            assertEquals(0, fixture.manifestRequests.get());
            assertEquals(0, fixture.fullRequests.get());
            assertEquals(0, fixture.deltaRequests.get());
            TreeDeltaEngine.verify(fixture.ready().resolve("tree"), fixture.target.files(), fixture.target.treeSha256());
            try (UpdatePreparer afterUi = fixture.preparer()) { assertTrue(afterUi.prepare()); }
            assertEquals(1, fixture.manifestRequests.get());
            assertEquals(0, fixture.fullRequests.get());
        }
    }

    @Test
    void emptyOrMissingPortableTargetRetainsInstalledTreeWithoutReady() throws Exception {
        for (int missing = -1; missing < NetPortableFixture.REQUIRED.size(); missing++) {
            try (Fixture fixture = new Fixture(temporary.resolve("incomplete-" + missing))) {
                var installedFiles = TreeDeltaEngine.inventory(fixture.installation);
                if (missing < 0) UpdateFiles.delete(fixture.targetRoot);
                else Files.delete(fixture.targetRoot.resolve(NetPortableFixture.REQUIRED.get(missing)));
                Files.createDirectories(fixture.targetRoot);
                fixture.refreshTargetArchive(4);
                // Размер и hashes совпадают; отказ касается именно состава portable target.
                TreeDeltaEngine.verify(fixture.targetRoot, fixture.target.files(), fixture.target.treeSha256());
                try (UpdatePreparer preparer = fixture.preparer()) { assertFalse(preparer.prepare()); }
                assertEquals(3, fixture.manifestRequests.get());
                assertEquals(0, fixture.fullRequests.get());
                assertEquals(0, fixture.deltaRequests.get());
                assertFalse(Files.exists(fixture.ready()));
                TreeDeltaEngine.verify(fixture.installation, installedFiles, fixture.current.treeSha256());
            }
        }
    }

    @Test
    void malformedNewTargetPreservesPreviouslyVerifiedReady() throws Exception {
        try (Fixture fixture = new Fixture(temporary)) {
            try (UpdatePreparer first = fixture.preparer()) { assertTrue(first.prepare()); }
            UpdateManifest cached = fixture.target;
            Files.delete(fixture.targetRoot.resolve("runtime/lib/modules"));
            fixture.refreshTargetArchive(4);
            try (UpdatePreparer next = fixture.preparer()) { assertTrue(next.prepare()); }
            assertEquals(4, fixture.manifestRequests.get());
            assertEquals(1, fixture.fullRequests.get());
            assertEquals(cached, UpdateCodec.read(Files.readString(fixture.ready().resolve("update.json"))));
            TreeDeltaEngine.verify(fixture.ready().resolve("tree"), cached.files(), cached.treeSha256());
        }
    }

    private static InstalledVersion version(Path root, int release, String commit) throws IOException {
        return new InstalledVersion(release, commit, TreeDeltaEngine.treeHash(TreeDeltaEngine.inventory(root)));
    }

    private static void awaitPartialDownload(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file) && Files.size(file) > 0) return;
            Thread.sleep(10);
        }
        fail("A real partial download must exist before cancellation");
    }

    private static void makeTree(Path root, String contents) throws IOException {
        NetPortableFixture.create(root);
        Files.writeString(root.resolve("app/data.txt"), contents);
        Files.writeString(root.resolve("runtime/jvm.txt"), "runtime-fixture");
    }

    private static byte[] zip(Path root, String replacement) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : TreeDeltaEngine.inventory(root)) {
                ZipEntry item = new ZipEntry("CashPrediction/" + entry.path());
                item.setTime(315532800000L);
                zip.putNextEntry(item);
                if (replacement != null && entry.path().equals("app/data.txt")) {
                    zip.write(replacement.getBytes(StandardCharsets.UTF_8));
                } else Files.copy(root.resolve(entry.path()), zip);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Сервер loopback с управляемыми задержками настоящего HTTP body. */
    private static final class Fixture implements AutoCloseable {
        final Path installation;
        final Path targetRoot;
        final InstalledVersion current;
        UpdateManifest target;
        volatile byte[] wireManifest;
        volatile byte[] fullBytes;
        volatile byte[] deltaBytes;
        volatile String redirect;
        volatile boolean slowFull;
        volatile boolean slowDelta;
        volatile boolean slowManifest;
        volatile boolean chunked;
        volatile int failManifestUntil;
        final AtomicInteger manifestRequests = new AtomicInteger();
        final AtomicInteger fullRequests = new AtomicInteger();
        final AtomicInteger deltaRequests = new AtomicInteger();
        final AtomicInteger rangeRequests = new AtomicInteger();
        final AtomicInteger forbiddenRequests = new AtomicInteger();
        final AtomicInteger redirectTargetRequests = new AtomicInteger();
        final Set<String> queries = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<String> deltaPaths = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch secondBodyStarted = new CountDownLatch(1);
        final CountDownLatch releaseBody = new CountDownLatch(1);
        final HttpServer server;
        final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
        final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

        Fixture(Path root) throws Exception {
            installation = root.resolve("installed");
            targetRoot = root.resolve("target");
            makeTree(installation, "base-a");
            makeTree(targetRoot, "target-c");
            current = version(installation, 1, COMMIT_A);
            fullBytes = zip(targetRoot, null);
            var files = TreeDeltaEngine.inventory(targetRoot);
            target = new UpdateManifest(3, COMMIT_C, "3", Instant.EPOCH, "CashPrediction-portable.zip",
                    fullBytes.length, hash(fullBytes), TreeDeltaEngine.treeHash(files), files, List.of());
            refreshManifest();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(handlers);
            server.createContext("/", this::handle);
            server.start();
        }

        Path updates() { return installation.resolve("CashMemory/Updates"); }
        Path ready() { return updates().resolve("Ready"); }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/update.json"); }

        UpdatePreparer preparer() { return preparer(Duration.ofSeconds(3), Duration.ZERO, Duration.ZERO); }

        UpdatePreparer preparer(Duration timeout, Duration first, Duration second) {
            return preparer(installation, current, timeout, first, second);
        }

        UpdatePreparer preparer(Path root, InstalledVersion installed, Duration timeout, Duration first, Duration second) {
            return new UpdatePreparer(root, installed, uri(), client, timeout, first, second);
        }

        void refreshManifest() throws IOException {
            wireManifest = UpdateCodec.write(target).getBytes(StandardCharsets.UTF_8);
        }

        void refreshTargetArchive(int release) throws Exception {
            fullBytes = zip(targetRoot, null);
            var files = TreeDeltaEngine.inventory(targetRoot);
            target = new UpdateManifest(release, "d".repeat(40), Integer.toString(release), Instant.EPOCH,
                    "CashPrediction-portable.zip", fullBytes.length, hash(fullBytes),
                    TreeDeltaEngine.treeHash(files), files, List.of());
            refreshManifest();
        }

        void setPatches(List<DeltaPatch> patches) throws IOException {
            target = new UpdateManifest(target.releaseNumber(), target.commitSha(), target.version(), target.publishedAtUtc(),
                    target.assetName(), target.sizeBytes(), target.sha256(), target.treeSha256(), target.files(), patches);
            refreshManifest();
        }

        DeltaPatch createDelta(Path base, InstalledVersion identity, String name) throws Exception {
            Path patch = targetRoot.getParent().resolve(name);
            TreeDeltaEngine.create(base, identity, targetRoot, target, patch);
            deltaBytes = Files.readAllBytes(patch);
            return new DeltaPatch(identity.releaseNumber(), identity.commitSha(), identity.treeSha256(),
                    name, deltaBytes.length, hash(deltaBytes));
        }

        void publishDelta(Path base, InstalledVersion identity, String name) throws Exception {
            setPatches(List.of(createDelta(base, identity, name)));
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (exchange.getRequestHeaders().containsKey("Range")) rangeRequests.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                byte[] body;
                boolean slow;
                if (path.equals("/update.json")) {
                    int attempt = manifestRequests.incrementAndGet();
                    String query = exchange.getRequestURI().getRawQuery();
                    if (query != null) queries.add(query);
                    if (redirect != null) {
                        exchange.getResponseHeaders().set("Location", redirect);
                        exchange.sendResponseHeaders(302, -1);
                        return;
                    }
                    if (attempt <= failManifestUntil) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                    body = wireManifest;
                    slow = slowManifest;
                } else if (path.equals("/manifest-target")) {
                    redirectTargetRequests.incrementAndGet();
                    body = wireManifest;
                    slow = false;
                } else if (path.equals("/CashPrediction-portable.zip")) {
                    int request = fullRequests.incrementAndGet();
                    if (request == 2) secondBodyStarted.countDown();
                    body = fullBytes;
                    slow = slowFull;
                } else if (path.endsWith(".cpdelta")) {
                    deltaRequests.incrementAndGet();
                    deltaPaths.add(path);
                    body = deltaBytes;
                    slow = slowDelta;
                } else {
                    forbiddenRequests.incrementAndGet();
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
                if (slow) {
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    bodyStarted.countDown();
                    if (!releaseBody.await(5, TimeUnit.SECONDS)) return;
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                } else exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                // Отмена клиента намеренно обрывает настоящий server socket.
            }
        }

        /** Закрывает сервер и все принадлежащие fixture фоновые задачи. */
        @Override public void close() {
            releaseBody.countDown();
            server.stop(0);
            client.shutdownNow();
            handlers.shutdownNow();
        }
    }
}
