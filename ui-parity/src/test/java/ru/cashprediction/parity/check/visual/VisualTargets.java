package ru.cashprediction.parity.check.visual;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import ru.cashprediction.parity.launch.ClientTarget;

/** Копирует разрешённый module path до запуска JVM, не удерживая jar общей сборки открытыми. */
public final class VisualTargets {
    private VisualTargets() { }

    /** Делает отдельный снимок каждого jar; не принимает изменившийся при копировании или повреждённый архив. */
    public static ClientTarget snapshot(ClientTarget target, Path directory) throws Exception {
        var paths = new ArrayList<Path>(); int index = 0;
        for (Path source : target.modulePath()) {
            if (!Files.isRegularFile(source)) throw new IllegalArgumentException("Snapshot requires packaged jar: " + source);
            Path destination = Files.createDirectories(directory.resolve(Integer.toString(index++))).resolve(source.getFileName());
            Files.copy(source, destination);
            if (Files.mismatch(source, destination) != -1) throw new IllegalStateException("Jar changed during snapshot: " + source);
            try (var zip = new ZipFile(destination.toFile())) {
                if (zip.getEntry("module-info.class") == null) throw new IllegalArgumentException("Missing module descriptor: " + source);
            }
            paths.add(destination);
        }
        return new ClientTarget(target.client(), paths, target.mainModule(), target.mainClass(), target.addModules(),
                target.jvmOptions(), target.arguments());
    }
}
