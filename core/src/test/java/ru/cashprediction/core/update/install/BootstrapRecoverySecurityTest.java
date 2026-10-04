package ru.cashprediction.core.update.install;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.update.model.FileEntry;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/**
 * Проверяет реальные файловые API журнала и предстартового барьера без запуска помощника.
 * Состояния аварии создаются средствами JDK, а запуск заменён счётчиком вызовов.
 * Синтетические exe не доказывают холодный запуск, исполнение отката или защиту от junction.
 * Неверные метаданные отвергаются; неполное дерево дополнительно требует false для fail-open lifecycle.
 */
class BootstrapRecoverySecurityTest {
    private static final List<String> CLIENTS = List.of("fx", "swing", "web");
    private static final List<String> REQUIRED_RUNTIME = List.of("runtime/bin/jli.dll",
            "runtime/bin/server/jvm.dll", "runtime/lib/modules");

    @TempDir Path temporary;

    /** Перенос runtime с обеими durable отметками запрещает UI каждого клиента и сохраняет резервные файлы. */
    @Test void interruptedRuntimeMoveDefersAllThreeClientsWithoutChangingRecoveryEvidence() throws Exception {
        for (String client : CLIENTS) {
            for (String state : List.of("BEFORE", "AFTER")) {
                BootstrapFixture f = fixture("runtime-" + client + "-" + state);
                interruptedRuntimeMove(f, state);
                Map<Path, byte[]> before = snapshot(f.root);
                AtomicInteger starts = new AtomicInteger();
                InstallCoordinator coordinator = coordinator(f, client, starts);

                assertFalse(coordinator.beforeUi(), client + ":" + state);
                assertEquals(1, starts.get());
                assertFalse(Files.exists(f.root.resolve("runtime/lib/modules"), LinkOption.NOFOLLOW_LINKS));
                List<FileEntry> runtime = f.old.stream().filter(file -> file.path().startsWith("runtime/")).toList();
                TreeDeltaEngine.verify(f.updates.resolve("Bootstrap"), runtime, TreeDeltaEngine.treeHash(runtime));
                try (var files = Files.list(f.updates.resolve("requests"))) {
                    List<Path> requests = files.toList();
                    assertEquals(1, requests.size());
                    Map<String, Object> request = InstallFiles.object(requests.getFirst());
                    assertEquals(client, request.get("client"));
                    assertEquals(f.journal.get("transactionId"), request.get("transactionId"));
                    assertEquals(List.of("--home", f.root.toString(), "--updated-from", f.target.commitSha()),
                            request.get("args"));
                }
                assertSnapshot(before);
                f.userData();
            }
        }
    }

    /** Подтверждённый старый инвентарь допускает UI, сохраняя посторонние файлы служебных каталогов. */
    @Test void verifiedRollbackAllowsAllThreeClientsAndKeepsForeignFiles() throws Exception {
        for (String client : CLIENTS) {
            for (String journal : List.of("install-journal.json", "completed-journal.json")) {
                BootstrapFixture f = fixture("rolled-back-" + client + "-" + journal);
                BootstrapFixture.put(f.updates, "Bootstrap/private.txt", "foreign-bootstrap");
                BootstrapFixture.put(f.updates, "Backup/private.txt", "foreign-backup");
                BootstrapFixture.put(f.updates, "Work-" + f.journal.get("transactionId") + "/private.txt", "foreign-work");
                rolledBack(f);
                if (journal.equals("completed-journal.json")) {
                    Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve(journal));
                }
                Map<Path, byte[]> before = snapshot(f.root);
                AtomicInteger starts = new AtomicInteger();

                assertTrue(coordinator(f, client, starts).beforeUi());
                assertEquals(1, starts.get());
                assertFalse(Files.exists(f.updates.resolve("requests"), LinkOption.NOFOLLOW_LINKS));
                TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
                assertSnapshot(before);
                f.userData();
            }
        }
    }

    /** Одной записи об откате недостаточно, если отсутствует лаунчер или обязательная часть runtime. */
    @Test void claimedRollbackWithMissingLauncherOrRuntimeNeverAllowsUi() throws Exception {
        for (String client : CLIENTS) {
            List<String> required = new ArrayList<>(REQUIRED_RUNTIME);
            required.add(RestartRequest.launcher(client));
            for (String path : required) {
                BootstrapFixture f = fixture("missing-" + client + "-" + path.replace('/', '-'));
                rolledBack(f);
                moveToBackup(f, path);
                assertBlockedBeforeUi(f, client);
            }
        }
    }

    /** Завершённый журнал обязан быть терминальным до разрешения UI и запуска очистки. */
    @Test void completedJournalWithInstallingPhaseIsRejectedBeforeUi() throws Exception {
        for (String client : CLIENTS) {
            BootstrapFixture f = fixture("completed-phase-" + client);
            f.journal.put("phase", "INSTALLING");
            InstallJournal.write(f.updates, f.journal);
            Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve("completed-journal.json"));
            assertRejectedBeforeUi(f, client);
        }
    }

    /** Завершённый откат проверяет фактический runtime так же, как ещё активный журнал отката. */
    @Test void completedRollbackDoesNotAllowUiWithMissingRuntimeModules() throws Exception {
        for (String client : CLIENTS) {
            BootstrapFixture f = fixture("completed-runtime-" + client);
            rolledBack(f);
            moveToBackup(f, "runtime/lib/modules");
            Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve("completed-journal.json"));
            assertBlockedBeforeUi(f, client);
        }
    }

    /** Непустой, но повреждённый runtime не считается работоспособным при наличии проверенного журнала. */
    @Test void claimedRollbackWithCorruptNonemptyRuntimeKeepsUiBlocked() throws Exception {
        for (String client : CLIENTS) {
            for (String journal : List.of("install-journal.json", "completed-journal.json")) {
                BootstrapFixture f = fixture("corrupt-runtime-" + client + "-" + journal);
                rolledBack(f);
                Path modules = f.root.resolve("runtime/lib/modules");
                Path backup = f.updates.resolve("Backup/runtime/lib/modules");
                Files.createDirectories(backup.getParent());
                Files.copy(modules, backup);
                Files.writeString(modules, "corrupt-but-nonempty-runtime");
                if (journal.equals("completed-journal.json")) {
                    Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve(journal));
                }
                assertBlockedBeforeUi(f, client);
            }
        }
    }

    /** Ошибка очистки после подтверждённого отката сохраняет запуск целой версии и все данные. */
    @Test void helperStartFailureAfterVerifiedRollbackDoesNotBlockUsableImage() throws Exception {
        for (String client : CLIENTS) {
            for (String journal : List.of("install-journal.json", "completed-journal.json")) {
                BootstrapFixture f = fixture("cleanup-failure-" + client + "-" + journal);
                rolledBack(f);
                if (journal.equals("completed-journal.json")) {
                    Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve(journal));
                }
                Map<Path, byte[]> before = snapshot(f.root);
                AtomicInteger starts = new AtomicInteger();
                InstallCoordinator coordinator = failingCoordinator(f, client, starts);
                assertTrue(coordinator.beforeUi());
                assertEquals(1, starts.get());
                assertFalse(Files.exists(f.updates.resolve("requests"), LinkOption.NOFOLLOW_LINKS));
                assertSnapshot(before);
                f.userData();
            }
        }
    }

    /** Ошибка запуска восстановителя при разрыве runtime не становится исключением, разрешающим UI. */
    @Test void helperStartFailureDuringRuntimeGapKeepsUiBlockedAndEvidenceIntact() throws Exception {
        for (String client : CLIENTS) {
            BootstrapFixture f = fixture("recovery-start-failure-" + client);
            interruptedRuntimeMove(f, "BEFORE");
            Map<Path, byte[]> before = snapshot(f.root);
            AtomicInteger starts = new AtomicInteger();
            assertFalse(failingCoordinator(f, client, starts).beforeUi());
            assertEquals(1, starts.get());
            try (var requests = Files.list(f.updates.resolve("requests"))) {
                assertEquals(1, requests.count());
            }
            assertSnapshot(before);
            f.userData();
        }
    }

    /** Повреждённый журнал поверх частичного дерева не проходит fail-open обёртку lifecycle. */
    @Test void invalidActiveAndCompletedMetadataWithRuntimeGapKeepsUiBlocked() throws Exception {
        for (String client : CLIENTS) {
            for (String journal : List.of("install-journal.json", "completed-journal.json")) {
                BootstrapFixture f = fixture("invalid-gap-" + client + "-" + journal);
                f.journal.put("unexpected", "foreign-metadata");
                InstallJournal.write(f.updates, f.journal);
                moveToBackup(f, "runtime/lib/modules");
                if (journal.equals("completed-journal.json")) {
                    Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve(journal));
                }
                assertBlockedBeforeUi(f, client);
            }
        }
    }

    /** Правильный терминальный UPDATED принимается только после совпадения полного целевого дерева. */
    @Test void completedUpdateAllowsAllClientsOnlyAfterTargetTreeVerification() throws Exception {
        for (String client : CLIENTS) {
            BootstrapFixture f = fixture("completed-target-" + client);
            for (FileEntry file : f.old) moveToBackup(f, file.path());
            for (FileEntry file : f.target.files()) {
                Path destination = f.root.resolve(file.path());
                Files.createDirectories(destination.getParent());
                Files.copy(f.ready.resolve(file.path()), destination);
            }
            updated(f);
            Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve("completed-journal.json"));
            Map<Path, byte[]> before = snapshot(f.root);
            AtomicInteger starts = new AtomicInteger();
            assertTrue(coordinator(f, client, starts).beforeUi());
            assertEquals(1, starts.get());
            assertFalse(Files.exists(f.updates.resolve("requests"), LinkOption.NOFOLLOW_LINKS));
            TreeDeltaEngine.verify(f.root, f.target.files(), f.target.treeSha256());
            assertSnapshot(before);
            f.userData();
        }
    }

    /** Терминальный UPDATED не объявляет целевым всё ещё работоспособное исходное дерево. */
    @Test void updatedJournalCannotAuthorizeCleanupWhenOnlyOldTreeMatches() throws Exception {
        for (String client : CLIENTS) {
            for (String journal : List.of("install-journal.json", "completed-journal.json")) {
                BootstrapFixture f = fixture("stale-updated-" + client + "-" + journal);
                updated(f);
                if (journal.equals("completed-journal.json")) {
                    Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve(journal));
                }
                assertRejectedBeforeUi(f, client);
                TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            }
        }
    }

    /** Схема 1 сохраняет фактическую грамматику без новых требований к отсутствовавшим cfg. */
    @Test void legacySchemaOneStillReadsPendingRollbackAndVerifiedCompletion() throws Exception {
        for (String client : CLIENTS) {
            HelperFixture f = new HelperFixture(temporary.resolve("legacy-" + client));
            f.journal.put("phase", "ROLLING_BACK");
            f.save();
            assertEquals("PENDING", InstallJournal.read(f.root, f.updates).get("outcome"));
            f.journal.put("outcome", "ROLLED_BACK");
            f.save();
            Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve("completed-journal.json"));
            assertEquals(1L, InstallJournal.readCompleted(f.root, f.updates).get("schemaVersion"));
            Map<Path, byte[]> before = snapshot(f.root);
            AtomicInteger starts = new AtomicInteger();
            InstallCoordinator coordinator = new InstallCoordinator(f.root, f.root.resolve("CashMemory"), client,
                    new String[0], 1, (root, updates) -> starts.incrementAndGet(), true);
            assertTrue(coordinator.beforeUi());
            assertEquals(1, starts.get());
            assertSnapshot(before);
            f.assertUserFiles();
        }
    }

    /** Неизвестные поля отклоняются до создания запроса, как и в ValidateJournal помощника. */
    @Test void unknownTopLevelJournalFieldsCannotBeHandedToHelper() throws Exception {
        for (String client : CLIENTS) {
            BootstrapFixture f = fixture("unknown-field-" + client);
            f.journal.put("unexpected", "foreign-metadata");
            InstallJournal.write(f.updates, f.journal);
            assertRejectedBeforeUi(f, client);
        }
    }

    /** Неверный outcome и противоречие phase/outcome не должны создавать цикл тихих перезапусков. */
    @Test void invalidOutcomeOrPhasePairCannotBeHandedToHelper() throws Exception {
        List<Map<String, Object>> variants = List.of(
                Map.of("phase", "PREPARED", "outcome", "FOREIGN"),
                Map.of("phase", "PREPARED", "outcome", "UPDATED"),
                Map.of("phase", "COMMITTED", "outcome", "PENDING"),
                Map.of("phase", "INSTALLING", "outcome", "ROLLED_BACK"),
                Map.of("phase", "PREPARED", "outcome", 1));
        for (String client : CLIENTS) {
            for (int i = 0; i <= variants.size(); i++) {
                BootstrapFixture f = fixture("outcome-" + client + "-" + i);
                if (i == variants.size()) f.journal.remove("outcome");
                else f.journal.putAll(variants.get(i));
                InstallJournal.write(f.updates, f.journal);
                assertRejectedBeforeUi(f, client);
            }
        }
    }

    /** UUID должен совпадать с канонической записью запроса и путей BootstrapPath. */
    @Test void nonCanonicalTransactionIdsCannotBeHandedToHelper() throws Exception {
        for (String client : CLIENTS) {
            for (String id : List.of("1-1-1-1-1", "ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB")) {
                BootstrapFixture f = fixture("transaction-" + client + "-" + id);
                f.journal.put("transactionId", id);
                InstallJournal.write(f.updates, f.journal);
                assertRejectedBeforeUi(f, client);
            }
        }
    }

    /** Пересчитанный SHA не делает безопасным cfg с внешним runtime, дубликатом или отсутствующим mainmodule. */
    @Test void selfConsistentHashesDoNotAuthorizeMalformedRedirectConfigs() throws Exception {
        for (String cfg : PortableBootstrap.CONFIGS) {
            for (String variant : List.of("external", "duplicate", "missing-module", "outside-section", "indented", "mixed-cr")) {
                BootstrapFixture f = fixture("cfg-" + cfg.replace('/', '-') + "-" + variant);
                String original = Files.readString(f.root.resolve(cfg));
                String module = PortableBootstrap.mainModule(original);
                String redirected = PortableBootstrap.redirect(original);
                String bad = switch (variant) {
                    case "external" -> "[Application]\napp.mainmodule=" + module
                            + "\napp.runtime=C:\\foreign\\runtime\n# app.runtime=" + PortableBootstrap.RUNTIME + "\n";
                    case "duplicate" -> redirected.replace("[Application]\r\n",
                            "[Application]\r\napp.runtime=C:\\foreign\\runtime\r\n");
                    case "outside-section" -> original + "\r\napp.runtime=" + PortableBootstrap.RUNTIME + "\r\n";
                    case "indented" -> redirected.replace("app.runtime=", " app.runtime=");
                    case "mixed-cr" -> redirected + "\r# malformed\r\n";
                    default -> redirected.replace("app.mainmodule=" + module + "\r\n", "");
                };
                replaceRedirect(f, cfg, bad);
                assertRejectedJournal(f);
            }
        }
    }

    /** Каждый из десяти обязательных файлов обязан иметь ненулевую длину в обоих инвентарях. */
    @Test void portableRecoveryRejectsEmptyRequiredFilesInEitherInventory() throws Exception {
        List<String> required = new ArrayList<>(PortableBootstrap.STABLE);
        required.addAll(REQUIRED_RUNTIME);
        for (String inventory : List.of("oldFiles", "target")) {
            for (String path : required) {
                BootstrapFixture f = fixture("empty-" + inventory + "-" + path.replace('/', '-'));
                List<FileEntry> files = (inventory.equals("oldFiles") ? f.old : f.target.files()).stream()
                        .map(file -> file.path().equals(path)
                                ? new FileEntry(path, 0, PortableBootstrap.sha(new byte[0]), file.readOnly()) : file).toList();
                replaceInventory(f, inventory, files);
                assertRejectedJournal(f);
            }
        }
    }

    /** Формально правильная строка treeSha256 должна описывать именно перечисленные файлы target. */
    @Test void journalRejectsTargetDigestThatDoesNotMatchItsInventory() throws Exception {
        BootstrapFixture f = fixture("target-digest");
        Map<String, Object> target = new LinkedHashMap<>(Json.asObject(f.journal.get("target"), "target"));
        target.put("treeSha256", "0".repeat(64));
        f.journal.put("target", target);
        InstallJournal.write(f.updates, f.journal);
        assertRejectedJournal(f);
    }

    /** Журнал восстановления требует три обязательных runtime-файла в исходном и целевом инвентарях. */
    @Test void portableRecoveryRejectsMissingRuntimeSentinelsInEitherInventory() throws Exception {
        for (String inventory : List.of("oldFiles", "target")) {
            for (String path : REQUIRED_RUNTIME) {
                BootstrapFixture f = fixture("inventory-" + inventory + "-" + path.replace('/', '-'));
                List<FileEntry> files = (inventory.equals("oldFiles") ? f.old : f.target.files()).stream()
                        .filter(file -> !file.path().equals(path)).toList();
                if (inventory.equals("oldFiles")) {
                    f.journal.put("oldFiles", files.stream().map(InstallJournal::entry).toList());
                    f.journal.put("oldTreeSha256", TreeDeltaEngine.treeHash(files));
                } else {
                    Map<String, Object> target = new LinkedHashMap<>(Json.asObject(f.journal.get("target"), "target"));
                    target.put("files", files.stream().map(InstallJournal::entry).toList());
                    target.put("treeSha256", TreeDeltaEngine.treeHash(files));
                    f.journal.put("target", target);
                }
                InstallJournal.write(f.updates, f.journal);
                assertRejectedJournal(f);
            }
        }
    }

    /** Операции не могут адресовать пользовательские данные, выход за корень, ADS или другой лаунчер. */
    @Test void recoveryOperationsRejectMaliciousPathsWithoutChangingAnyFiles() throws Exception {
        List<String> paths = List.of("CashMemory/plans/user.md", "private.txt", "app/../../CashMemory/settings.md",
                "runtime/../app/CashPrediction.cfg", "app/evil:stream", "C:/foreign/user.md",
                "app\\..\\CashMemory\\settings.md", "CashPrediction-Evil.exe");
        for (int i = 0; i < paths.size(); i++) {
            BootstrapFixture f = fixture("operation-" + i);
            f.journal.put("operations", List.of(Map.of("kind", "RESTORE", "path", paths.get(i), "state", "BEFORE")));
            InstallJournal.write(f.updates, f.journal);
            assertRejectedJournal(f);
        }
    }

    /** Ссылка вместо завершённого журнала отвергается до разрешения UI каждого клиента. */
    @Test void completedJournalSymbolicLinkCannotBypassPrestartGuard() throws Exception {
        for (String client : CLIENTS) {
            BootstrapFixture f = fixture("completed-link-" + client);
            Path external = Files.createDirectory(temporary.resolve("external-journal-" + client)).resolve("journal.json");
            Files.copy(f.updates.resolve("install-journal.json"), external);
            Files.move(f.updates.resolve("install-journal.json"), f.updates.resolve("held-journal.json"));
            Path link = f.updates.resolve("completed-journal.json");
            symbolicLink(link, external);
            byte[] outside = Files.readAllBytes(external);
            try {
                assertAll(() -> assertRejectedBeforeUi(f, client),
                        () -> assertArrayEquals(outside, Files.readAllBytes(external)));
            } finally {
                Files.delete(link);
            }
        }
    }

    /** Подмена любого исходного cfg ссылкой не позволяет плану читать внешние данные. */
    @Test void configSourceSymbolicLinksAreRejectedWithoutTouchingExternalFiles() throws Exception {
        for (String cfg : PortableBootstrap.CONFIGS) {
            BootstrapFixture f = fixture("source-link-" + cfg.replace('/', '-'));
            Path external = temporary.resolve("external-" + Path.of(cfg).getFileName());
            Files.copy(f.root.resolve(cfg), external);
            Path link = f.root.resolve(cfg);
            Files.move(link, f.updates.resolve("held.cfg"));
            symbolicLink(link, external);
            Map<Path, byte[]> before = snapshot(f.root);
            byte[] outside = Files.readAllBytes(external);
            try {
                assertAll(() -> assertThrows(IOException.class, () -> PortableBootstrap.plan(f.root, f.target, f.old)),
                        () -> assertSnapshot(before),
                        () -> assertArrayEquals(outside, Files.readAllBytes(external)));
            } finally {
                Files.delete(link);
            }
        }
    }

    /** Ссылка в предке Updates отклоняется ещё конструктором, до регистрации и записи запросов. */
    @Test void updatesAncestorSymbolicLinkIsRejectedWithoutWritingOutsideRoot() throws Exception {
        BootstrapFixture f = fixture("updates-link");
        Path external = Files.createDirectory(temporary.resolve("external-updates"));
        Path sentinel = Files.writeString(external.resolve("private.txt"), "outside-data");
        Files.move(f.updates, f.root.resolve("held-updates"));
        symbolicLink(f.updates, external);
        Map<Path, byte[]> before = snapshot(f.root);
        try {
            for (String client : CLIENTS) {
                assertThrows(IOException.class, () -> coordinator(f, client, new AtomicInteger()));
            }
            assertEquals("outside-data", Files.readString(sentinel));
            try (var files = Files.list(external)) {
                assertEquals(List.of(sentinel), files.toList());
            }
            assertSnapshot(before);
        } finally {
            Files.delete(f.updates);
        }
    }

    /** Использует только подготовку общей fixture: её методы запуска процессов никогда не вызываются. */
    private BootstrapFixture fixture(String name) throws IOException {
        BootstrapFixture f = new BootstrapFixture(temporary.resolve(name));
        assertEquals("PENDING", InstallJournal.read(f.root, f.updates).get("outcome"));
        return f;
    }

    /** Проверяет настоящий координатор, подменяя только запуск отдельного процесса. */
    private static InstallCoordinator coordinator(BootstrapFixture f, String client, AtomicInteger starts) throws IOException {
        return new InstallCoordinator(f.root, f.root.resolve("CashMemory"), client,
                new String[]{"--selftest", "--registry-node", "foreign", "--updated-from", f.target.commitSha()},
                1, (root, updates) -> starts.incrementAndGet(), true);
    }

    /** Ошибка отдельного процесса моделируется проверяемым исключением без исполнения PowerShell. */
    private static InstallCoordinator failingCoordinator(BootstrapFixture f, String client, AtomicInteger starts) throws IOException {
        return new InstallCoordinator(f.root, f.root.resolve("CashMemory"), client, new String[0], 1,
                (root, updates) -> {
                    starts.incrementAndGet();
                    throw new IOException("HELPER_START_FAILED");
                }, true);
    }

    /** false, а не исключение, сохраняет запрет UI через неизменённый fail-open API lifecycle. */
    private static void assertBlockedBeforeUi(BootstrapFixture f, String client) throws IOException {
        Map<Path, byte[]> before = snapshot(f.root);
        AtomicInteger starts = new AtomicInteger();
        InstallCoordinator coordinator = coordinator(f, client, starts);
        assertAll(client,
                () -> assertFalse(coordinator.beforeUi(), "PARTIAL_TREE_MUST_NOT_ENTER_UI"),
                () -> assertEquals(0, starts.get(), "HELPER_MUST_NOT_START"),
                () -> assertFalse(Files.exists(f.updates.resolve("requests"), LinkOption.NOFOLLOW_LINKS), "NO_RESTART_REQUEST"),
                () -> assertSnapshot(before), f::userData);
    }

    /** Отказ должен предшествовать запуску и запросу; проверка сохранности выполняется и при провале регрессии. */
    private static void assertRejectedBeforeUi(BootstrapFixture f, String client) throws IOException {
        Map<Path, byte[]> before = snapshot(f.root);
        AtomicInteger starts = new AtomicInteger();
        InstallCoordinator coordinator = coordinator(f, client, starts);
        assertAll(client,
                () -> assertThrows(IOException.class, coordinator::beforeUi),
                () -> assertEquals(0, starts.get(), "HELPER_MUST_NOT_START"),
                () -> assertFalse(Files.exists(f.updates.resolve("requests"), LinkOption.NOFOLLOW_LINKS), "NO_RESTART_REQUEST"),
                () -> assertSnapshot(before),
                f::userData);
    }

    /** Проверяет отказ реального читателя журнала без каких-либо операций помощника. */
    private static void assertRejectedJournal(BootstrapFixture f) throws IOException {
        Map<Path, byte[]> before = snapshot(f.root);
        assertAll(() -> assertThrows(IOException.class, () -> InstallJournal.read(f.root, f.updates)),
                () -> assertSnapshot(before), f::userData);
    }

    /** Копирует immutable bootstrap перед изменением только тестового журнала. */
    private static Map<String, Object> bootstrap(BootstrapFixture f) {
        Map<String, Object> value = new LinkedHashMap<>(Json.asObject(f.journal.get("bootstrap"), "bootstrap"));
        f.journal.put("bootstrap", value);
        return value;
    }

    /** Создаёт терминальную отметку без исполнения отката; фактический старый инвентарь проверяет координатор. */
    private static void rolledBack(BootstrapFixture f) throws IOException {
        f.journal.put("phase", "ROLLING_BACK");
        f.journal.put("outcome", "ROLLED_BACK");
        bootstrap(f).put("state", "RESTORED");
        InstallJournal.write(f.updates, f.journal);
    }

    /** Создаёт отметку UPDATED; проверяемый координатор обязан отдельно подтвердить целевое дерево. */
    private static void updated(BootstrapFixture f) throws IOException {
        f.journal.put("phase", "COMMITTED");
        f.journal.put("outcome", "UPDATED");
        bootstrap(f).put("state", "RESTORED");
        InstallJournal.write(f.updates, f.journal);
    }

    /** Пересчитывает инвентарь и его хеш без изменения фактического дерева fixture. */
    private static void replaceInventory(BootstrapFixture f, String inventory, List<FileEntry> files) throws IOException {
        if (inventory.equals("oldFiles")) {
            f.journal.put("oldFiles", files.stream().map(InstallJournal::entry).toList());
            f.journal.put("oldTreeSha256", TreeDeltaEngine.treeHash(files));
        } else {
            Map<String, Object> target = new LinkedHashMap<>(Json.asObject(f.journal.get("target"), "target"));
            target.put("files", files.stream().map(InstallJournal::entry).toList());
            target.put("treeSha256", TreeDeltaEngine.treeHash(files));
            f.journal.put("target", target);
        }
        InstallJournal.write(f.updates, f.journal);
    }

    /** Сохраняет оригинальный файл в Backup, моделируя разрыв исходного дерева без удаления данных. */
    private static void moveToBackup(BootstrapFixture f, String path) throws IOException {
        Path destination = f.updates.resolve("Backup").resolve(path);
        Files.createDirectories(destination.getParent());
        Files.move(f.root.resolve(path), destination);
    }

    /** Воссоздаёт файловое состояние после переноса modules, до или после второй записи операции. */
    private static void interruptedRuntimeMove(BootstrapFixture f, String durableState) throws IOException {
        List<Map<String, Object>> operations = new ArrayList<>();
        for (FileEntry file : f.old) {
            boolean runtime = file.path().startsWith("runtime/");
            if (!runtime && !PortableBootstrap.STABLE.contains(file.path())) continue;
            Path base = f.updates.resolve(runtime ? "Bootstrap" : "Backup");
            Path destination = base.resolve(file.path());
            Files.createDirectories(destination.getParent());
            Files.copy(f.root.resolve(file.path()), destination);
            operations.add(Map.of("kind", runtime ? "BOOT_COPY" : "BACKUP_COPY", "path", file.path(), "state", "AFTER"));
        }
        InstallFiles.write(f.updates.resolve("Bootstrap/owner.json"), ru.cashprediction.core.json.JsonWriter.write(
                Map.of("schemaVersion", 1, "installationRoot", f.root.toString(), "transactionId", f.journal.get("transactionId"))));
        Map<String, Object> b = bootstrap(f);
        for (Object value : (List<?>) b.get("cfgTexts")) {
            Map<String, Object> cfg = Json.asObject(value, "cfg");
            String path = (String) cfg.get("path");
            Files.writeString(f.root.resolve(path), (String) cfg.get("text"));
            operations.add(Map.of("kind", "REDIRECT", "path", path, "state", "AFTER"));
        }
        moveToBackup(f, "runtime/lib/modules");
        operations.add(Map.of("kind", "BACKUP", "path", "runtime/lib/modules", "state", durableState));
        b.put("state", "ACTIVE");
        b.put("publishState", "AFTER");
        f.journal.put("phase", "BACKING_UP");
        f.journal.put("operations", operations);
        InstallJournal.write(f.updates, f.journal);
    }

    /** Подменяет cfg и его SHA согласованно, чтобы отказ не объяснялся простой порчей хеша. */
    private static void replaceRedirect(BootstrapFixture f, String path, String text) throws IOException {
        Map<String, Object> b = bootstrap(f);
        List<FileEntry> entries = UpdateCodec.readFiles(b.get("redirectFiles"));
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        b.put("redirectFiles", entries.stream().map(file -> file.path().equals(path)
                ? InstallJournal.entry(new FileEntry(path, bytes.length, PortableBootstrap.sha(bytes), file.readOnly()))
                : InstallJournal.entry(file)).toList());
        b.put("cfgTexts", ((List<?>) b.get("cfgTexts")).stream().map(value -> {
            Map<String, Object> cfg = Json.asObject(value, "cfg");
            return path.equals(cfg.get("path")) ? Map.of("path", path, "text", text) : cfg;
        }).toList());
        InstallJournal.write(f.updates, f.journal);
    }

    /** Снимает байты всех существующих обычных файлов, включая Ready, Backup и посторонние служебные файлы. */
    private static Map<Path, byte[]> snapshot(Path root) throws IOException {
        Map<Path, byte[]> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                result.put(path, Files.readAllBytes(path));
            }
        }
        return result;
    }

    /** Сравнивает исходные байты без зависимости от проверяемых SHA и не следует подменённым ссылкам. */
    private static void assertSnapshot(Map<Path, byte[]> before) throws IOException {
        for (Map.Entry<Path, byte[]> file : before.entrySet()) {
            assertTrue(Files.isRegularFile(file.getKey(), LinkOption.NOFOLLOW_LINKS), file.getKey().toString());
            assertArrayEquals(file.getValue(), Files.readAllBytes(file.getKey()), file.getKey().toString());
        }
    }

    /** Пропуск возможен только при неподдерживаемом API или явном отказе Windows в привилегии symlink. */
    private static void symbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException unavailable) {
            assumeTrue(false, "SYMLINK_API_UNAVAILABLE: " + unavailable);
        } catch (FileSystemException unavailable) {
            String reason = String.valueOf(unavailable.getReason()).strip().toLowerCase(Locale.ROOT);
            if (reason.endsWith(".")) reason = reason.substring(0, reason.length() - 1);
            // Только известный отказ в привилегии; общий отказ доступа и прочие ошибки не скрываются.
            if (System.getProperty("os.name", "").startsWith("Windows")
                    && (reason.equals("a required privilege is not held by the client")
                    || reason.equals("клиент не обладает требуемыми правами"))) {
                assumeTrue(false, "SYMLINK_PRIVILEGE_UNAVAILABLE: " + unavailable);
            }
            throw unavailable;
        }
    }
}
