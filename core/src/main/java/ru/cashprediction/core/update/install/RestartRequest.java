package ru.cashprediction.core.update.install;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.cashprediction.core.json.JsonWriter;

/** Запрос запуска строго выбранного клиента после установки или отката. */
public final class RestartRequest {
    private RestartRequest() { }

    /** Возвращает имя лаунчера из закрытого списка клиентов. */
    public static String launcher(String client) {
        return switch (client) {
            case "fx" -> "CashPrediction.exe";
            case "swing" -> "CashPrediction-Swing.exe";
            case "web" -> "CashPrediction-Web.exe";
            default -> throw new IllegalArgumentException("CLIENT_ID");
        };
    }

    /** Переносит только разрешённые аргументы; произвольные пути и режимы тестов отбрасывает. */
    public static List<String> safeArgs(Path root, String client, String[] args, String targetSha) {
        launcher(client);
        if (!targetSha.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("TARGET_SHA");
        List<String> safe = new ArrayList<>(List.of("--home", root.toString()));
        if (client.equals("web")) {
            for (String flag : List.of("--no-browser", "--no-window")) {
                for (String arg : args) {
                    if (flag.equals(arg)) { safe.add(flag); break; }
                }
            }
        }
        safe.add("--updated-from");
        safe.add(targetSha);
        return List.copyOf(safe);
    }

    static void write(Path updates, UUID transaction, Path root, String client,
                      String[] args, String targetSha) throws IOException {
        UUID request = UUID.randomUUID();
        InstallFiles.write(updates.resolve("requests").resolve(request + ".json"),
                JsonWriter.write(Map.of("schemaVersion", 1, "transactionId", transaction.toString(),
                        "requestId", request.toString(), "client", client,
                        "args", safeArgs(root, client, args, targetSha))));
    }
}
