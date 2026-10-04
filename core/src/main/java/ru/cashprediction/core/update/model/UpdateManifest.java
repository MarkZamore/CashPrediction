package ru.cashprediction.core.update.model;

import java.time.Instant;
import java.util.List;

/** Неизменяемый манифест схемы 2 с защитными копиями списков. */
public record UpdateManifest(int releaseNumber, String commitSha, String version,
                             Instant publishedAtUtc, String assetName, long sizeBytes,
                             String sha256, String treeSha256, List<FileEntry> files,
                             List<DeltaPatch> deltaPatches) {
    /** Копирует списки, исключая изменение манифеста через исходные коллекции. */
    public UpdateManifest {
        files = List.copyOf(files);
        deltaPatches = List.copyOf(deltaPatches);
    }
}
