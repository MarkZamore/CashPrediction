package ru.cashprediction.parity.dummy;

import java.nio.file.*;
import java.util.*;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.json.*;

/** Фиктивный клиент только для проверки стенда: копирует тестовые golden и намеренно меняет один счётчик. */
public final class FakeParityClientMain {
    private FakeParityClientMain() { }
    /** Пишет дампы и журнал завершения; не запускает интерфейс и не доказывает его паритет. */
    public static void main(String[] args) throws Exception {
        LaunchOptions options = LaunchOptions.parse(args);
        String scenario = options.selftest();
        Path source = Path.of(System.getProperty("parity.fake.goldens")).resolve(scenario);
        Path output = options.selftestOut().resolve(scenario); Files.createDirectories(output);
        try (var files = Files.list(source)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                Map<String, Object> tree = new LinkedHashMap<>(Json.asObject(JsonParser.parse(Files.readString(path)), "dump"));
                tree.put("client", System.getProperty("parity.fake.client"));
                if (Boolean.getBoolean("parity.fake.mutate")) tree.put("counters", Map.of("unexpected", 1));
                Files.writeString(output.resolve(path.getFileName()), JsonWriter.write(tree));
            }
        }
        Files.writeString(options.selftestOut().resolve("selftest.log"), "SELFTEST 1 OK sample\nSELFTEST DONE\n");
    }
}
