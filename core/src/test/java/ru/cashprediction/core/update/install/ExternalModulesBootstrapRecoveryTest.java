package ru.cashprediction.core.update.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.tree.TreeDeltaEngine;

/** Реальный PowerShell на синтетических файлах проверяет сохранение модулей; нативный запуск не имитируется. */
class ExternalModulesBootstrapRecoveryTest {
    @TempDir Path temporary;

    @Test void successfulExternalModuleInstallationCleansProtectedCopies() throws Exception {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        BootstrapFixture f = new BootstrapFixture(temporary.resolve("success"), true);
        var result = f.run(0, false, "", 0);
        assertEquals(0, result.exit(), result.diagnostics());
        TreeDeltaEngine.verify(f.root, f.target.files(), f.target.treeSha256());
        assertFalse(Files.exists(f.updates.resolve("Bootstrap")));
        f.userData();
    }

    @Test void crashAfterMovingApplicationFilesLeavesCompleteProtectedModulesForRecovery() throws Exception {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        for (String phase : List.of("INSTALLING", "VERIFYING")) {
            BootstrapFixture f = new BootstrapFixture(temporary.resolve(phase), true);
            var crash = f.run(0, true, phase, 0);
            assertNotEquals(0, crash.exit(), crash.diagnostics());
            var protectedFiles = f.old.stream().filter(file -> PortableBootstrap.protectedPayload(file.path())).toList();
            TreeDeltaEngine.verify(f.updates.resolve("Bootstrap"), protectedFiles, TreeDeltaEngine.treeHash(protectedFiles));
            for (String cfg : PortableBootstrap.CONFIGS) {
                assertTrue(Files.readString(f.root.resolve(cfg)).contains("java-options=" + PortableBootstrap.MODULES));
            }
            var recovery = f.run(0, false, "", 0);
            assertEquals(0, recovery.exit(), recovery.diagnostics());
            TreeDeltaEngine.verify(f.root, f.old, f.oldHash);
            assertFalse(Files.exists(f.updates.resolve("Bootstrap")));
            f.userData();
        }
    }
}
