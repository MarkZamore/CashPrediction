package ru.cashprediction.core.update.install;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import ru.cashprediction.core.json.JsonWriter;

/** Регистрация реальной JVM с временем рождения, независимая от родителя jpackage. */
final class ProcessLease {
    private ProcessLease() { }

    static void register(Path root, Path updates, String client) throws IOException {
        ProcessHandle process = ProcessHandle.current();
        long birth = process.info().startInstant().orElseThrow(() -> new IOException("PROCESS_BIRTH_UNKNOWN"))
                .toEpochMilli();
        UUID id = UUID.randomUUID();
        InstallFiles.write(updates.resolve("processes").resolve(id + ".json"),
                JsonWriter.write(Map.of("schemaVersion", 1, "leaseId", id.toString(), "pid", process.pid(),
                        "startedAtEpochMillis", birth, "installationRoot", root.toString(), "client", client)));
        // close не удаляет lease: JVM и её DLL остаются живы до фактического выхода процесса.
    }
}
