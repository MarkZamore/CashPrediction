package ru.cashprediction.core.update.install;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Строгий план bootstrap сохраняет mainmodule, параметры Java и исходный APPDIR. */
class PortableBootstrapTest {
    @TempDir Path temporary;

    @Test void redirectOnlyChangesRuntimePropertyAndPreservesAllOtherLines() throws Exception {
        String original = "[Application]\r\napp.mainmodule=ru.example/ru.example.Main\r\n\r\n[JavaOptions]\r\njava-options=-Xmx512m\r\n";
        String redirected = PortableBootstrap.redirect(original);
        assertEquals(original, redirected.replace("app.runtime=" + PortableBootstrap.RUNTIME + "\r\n", ""));
        assertEquals("ru.example/ru.example.Main", PortableBootstrap.mainModule(original));
        assertTrue(redirected.contains("app.runtime=$ROOTDIR\\CashMemory\\Updates\\Bootstrap\\runtime"));
        assertFalse(redirected.contains("$APPDIR\\CashMemory"));
    }

    @Test void duplicateRuntimeOrExternalModulePathsAreRejected() {
        String application = "[Application]\napp.mainmodule=ru.example/ru.example.Main\n";
        for (String bad : List.of("app.runtime=C:\\foreign\\runtime\n", "app.mainjar=foreign.jar\n",
                "app.modulepath=$APPDIR\n", "app.classpath=$APPDIR\n", "app.runtime=$ROOTDIR/runtime\napp.runtime=$ROOTDIR/runtime\n")) {
            assertThrows(IOException.class, () -> PortableBootstrap.redirect(application + bad));
        }
        assertThrows(IOException.class, () -> PortableBootstrap.redirect(application + application));
    }

    @Test void externalModulesAreRedirectedAlongWithTheirIndependentRuntime() throws Exception {
        String original = "[Application]\napp.mainmodule=ru.example/ru.example.Main\n[JavaOptions]\n"
                + "java-options=--module-path\njava-options=$APPDIR\njava-options=-Xmx512m\n";
        String redirected = PortableBootstrap.redirect(original);
        assertTrue(redirected.contains("java-options=" + PortableBootstrap.MODULES));
        assertFalse(redirected.contains("java-options=$APPDIR"));
        assertTrue(PortableBootstrap.protectedPayload("app/cashprediction-core-1.0.0.jar"));
        assertFalse(PortableBootstrap.protectedPayload("app/foreign.jar"));
        assertFalse(PortableBootstrap.protectedPayload("CashMemory/private.jar"));
        for (String path : List.of("C:\\foreign", "$APPDIR\\mods", "$ROOTDIR", "")) {
            assertThrows(IOException.class, () -> PortableBootstrap.redirect(original.replace("java-options=$APPDIR", "java-options=" + path)));
        }
        assertThrows(IOException.class, () -> PortableBootstrap.redirect(original + "java-options=--module-path\njava-options=$APPDIR\n"));
    }

    @Test void externalModuleJournalIsReadableAndAllFourModulesAreManaged() throws Exception {
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("external"), true);
        var journal = InstallJournal.read(f.root, f.updates);
        var bootstrap = Json.asObject(journal.get("bootstrap"), "bootstrap");
        assertEquals(4, f.old.stream().filter(file -> file.path().startsWith("app/")
                && PortableBootstrap.protectedPayload(file.path())).count());
        for (Object value : (List<?>) bootstrap.get("cfgTexts")) {
            assertTrue(Json.asObject(value, "cfg").get("text").toString().contains("java-options=" + PortableBootstrap.MODULES));
        }
    }

    @Test void duplicateCoreVersionsCannotSubstituteForMissingWebModule() throws Exception {
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("roles"), true);
        List<ru.cashprediction.core.update.model.FileEntry> malformed = new java.util.ArrayList<>(f.old.stream()
                .filter(file -> !file.path().contains("cashprediction-web-")).toList());
        var core = f.old.stream().filter(file -> file.path().contains("cashprediction-core-")).findFirst().orElseThrow();
        malformed.add(new ru.cashprediction.core.update.model.FileEntry("app/cashprediction-core-2.0.0.jar",
                core.sizeBytes(), core.sha256(), core.readOnly()));
        assertThrows(IOException.class, () -> PortableBootstrap.requireModules(malformed));
    }

    @Test void portableJournalHasExactBoundedPlanAndGenericJournalKeepsSchemaOne() throws Exception {
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("plan"));
        var journal = InstallJournal.read(f.root, f.updates);
        assertEquals(2L, journal.get("schemaVersion"));
        var bootstrap = Json.asObject(journal.get("bootstrap"), "bootstrap");
        assertEquals("INITIAL", bootstrap.get("state"));
        assertEquals("NONE", bootstrap.get("publishState"));
        assertEquals(3, UpdateCodec.readFiles(bootstrap.get("redirectFiles")).size());
        assertEquals(1, InstallJournal.prepare(f.root, f.target).get("schemaVersion"));
        f.userData();
    }

    @Test void collisionDoesNotMutateExistingImageOrForeignBootstrapData() throws Exception {
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("collision"));
        BootstrapFixture.put(f.updates, "Bootstrap/private.txt", "foreign-data");
        assertThrows(IOException.class, () -> InstallJournal.preparePortable(f.root, f.target));
        assertEquals("foreign-data", Files.readString(f.updates.resolve("Bootstrap/private.txt")));
        TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
        f.userData();
    }

    @Test void productionCoordinatorRefusesIncompleteImageBeforePublishingJournal() throws Exception {
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("required"));
        Files.delete(f.updates.resolve("install-journal.json"));
        Files.delete(f.ready.resolve("runtime/bin/jli.dll"));
        var coordinator = new InstallCoordinator(f.root, f.root.resolve("CashMemory"), "fx", new String[0],
                1, (root, updates) -> fail("INCOMPLETE_BOOTSTRAP_LAUNCHED"), true);
        assertTrue(coordinator.beforeUi());
        assertFalse(Files.exists(f.updates.resolve("install-journal.json")));
        f.userData();
    }
}
