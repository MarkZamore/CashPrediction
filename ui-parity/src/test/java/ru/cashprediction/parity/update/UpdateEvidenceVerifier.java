package ru.cashprediction.parity.update;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateValidation;

/**
 * JDK-only CLI итогового results.json, без запуска клиентов и изменения статусов.
 *
 * <p>Пример после компиляции в отдельную папку: {@code java -cp <classes>;<core.jar>
 * ru.cashprediction.parity.update.UpdateEvidenceVerifier C:\evidence\results.json}.
 * Во время live UI сборка разрешена только в изолированном Temp. На classpath нужны
 * этот класс, UpdateEvidence, FixtureAuthority и закреплённое ядро; JUnit для CLI не нужен.
 * Корневой envelope проверяется здесь, вся canonical матрица и artifacts исключительно
 * в {@link UpdateEvidence#requireSignoff}. Файл и payload не переписываются.
 */
public final class UpdateEvidenceVerifier {
    private static final Set<String> SAFE_CODES = Set.of(
            "RESULTS_ARGUMENT", "RESULTS_PATH", "RESULTS_FILE", "RESULTS_SIZE",
            "RESULTS_SCHEMA", "RESULTS_STATUS", "RESULTS_UTF8", "OBJECT_FIELDS",
            "OBJECT_REQUIRED", "STRING_REQUIRED", "INTEGER_REQUIRED", "ARRAY_REQUIRED",
            "INVALID_JSON", "JSON_SIZE", "EVIDENCE_CELL_IDENTITY", "EVIDENCE_PENDING_OR_FAILED",
            "EVIDENCE_EMPTY", "EVIDENCE_COMMIT", "EVIDENCE_FAILURE", "EVIDENCE_ARGS",
            "EVIDENCE_CLIENT", "EVIDENCE_EXE", "EVIDENCE_TIME", "EVIDENCE_TIME_ORDER",
            "EVIDENCE_PARTIAL_TREE", "EVIDENCE_USER_CHANGED", "EVIDENCE_PHASE_MISSING",
            "EVIDENCE_MISSING_CELL", "EVIDENCE_ARTIFACT", "FIXTURE_LINK");

    private UpdateEvidenceVerifier() { }

    /**
     * Проверяет существующий абсолютный results.json и возвращает число принятых cells.
     *
     * @param results абсолютный путь к results.json
     * @return число cells после полной authoritative проверки
     * @throws IOException при неверном envelope, pending, неполной матрице или artifacts
     */
    public static int verify(Path results) throws IOException {
        if (results == null || !results.isAbsolute() || results.getFileName() == null
                || !results.getFileName().toString().equals("results.json")) {
            throw new IOException("RESULTS_PATH");
        }
        FixtureAuthority.noLinks(results);
        if (!Files.isRegularFile(results)) throw new IOException("RESULTS_FILE");
        if (Files.size(results) > UpdateValidation.MAX_JSON) throw new IOException("RESULTS_SIZE");
        byte[] bytes;
        // Размер read ограничен независимо от предварительного stat и возможного роста файла.
        try (var input = Files.newInputStream(results)) {
            bytes = input.readNBytes(UpdateValidation.MAX_JSON + 1);
        }
        if (bytes.length > UpdateValidation.MAX_JSON) throw new IOException("RESULTS_SIZE");
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("RESULTS_UTF8", invalid);
        }
        Map<String, Object> envelope = UpdateCodec.object(UpdateCodec.parse(json));
        UpdateCodec.keys(envelope, "schemaVersion", "status", "cells");
        if (UpdateCodec.number(envelope, "schemaVersion") != 1) throw new IOException("RESULTS_SCHEMA");
        if (!UpdateCodec.string(envelope, "status").equals("PASS")) throw new IOException("RESULTS_STATUS");
        var rows = new ArrayList<Map<String, Object>>();
        for (Object cell : UpdateCodec.array(envelope.get("cells"))) rows.add(UpdateCodec.object(cell));
        // Никакого второго списка фаз/cells или ослабленной проверки artifacts в CLI.
        UpdateEvidence.requireSignoff(rows);
        return rows.size();
    }

    /**
     * Исполняет CLI без System.exit для focused тестов и embedding в JDK harness.
     *
     * @param args ровно один абсолютный путь
     * @param output поток краткого успешного результата
     * @param errors поток одного безопасного diagnostic code без paths/payload/stack trace
     * @return 0 при полной приёмке, 1 при отказе, 2 при неверном числе аргументов
     */
    public static int run(String[] args, PrintStream output, PrintStream errors) {
        if (args == null || args.length != 1) {
            errors.println("UPDATE_EVIDENCE_REJECTED code=RESULTS_ARGUMENT");
            return 2;
        }
        try {
            int count = verify(Path.of(args[0]));
            output.println("UPDATE_EVIDENCE_VERIFIED cells=" + count);
            return 0;
        } catch (IOException | RuntimeException invalid) {
            // Не выводим сообщения OS/parser: в них могут оказаться пути и пользовательские данные.
            String message = invalid.getMessage();
            String code = message != null && SAFE_CODES.contains(message) ? message : "RESULTS_INVALID";
            errors.println("UPDATE_EVIDENCE_REJECTED code=" + code);
            return 1;
        } catch (LinkageError unavailable) {
            errors.println("UPDATE_EVIDENCE_REJECTED code=RESULTS_DEPENDENCY");
            return 1;
        }
    }

    /**
     * Принимает единственный путь и возвращает ОС ненулевой exit при любом отказе.
     *
     * @param args абсолютный путь к results.json
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
