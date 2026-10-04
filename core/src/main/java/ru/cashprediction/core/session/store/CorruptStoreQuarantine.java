package ru.cashprediction.core.session.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import ru.cashprediction.core.io.CanonicalPaths;
import ru.cashprediction.core.text.Texts;

/** Проверяемый уникальный диагностический карантин; исходники не удаляет. */
final class CorruptStoreQuarantine {
    private CorruptStoreQuarantine() { }

    /** Читает точные байты файлов только внутри CashMemory, включая журнал и sidecar. */
    static Map<String, byte[]> files(Path root, Path... paths) throws IOException {
        Path safe = root(root);
        Map<String, byte[]> result = new TreeMap<>();
        for (Path path : paths) {
            Path file = CanonicalPaths.requireNoLinks(path.toAbsolutePath().normalize());
            if (!file.getParent().equals(safe)) throw new IOException("CASHMEMORY_SOURCE");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) result.put(file.getFileName().toString(), Files.readAllBytes(file));
        }
        return result;
    }

    /** Сохраняет логические UTF-16 code units реестра без потери непарных суррогатов. */
    static Map<String, byte[]> registry(RegistryBackend backend) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        for (String key : backend.keys()) {
            String value = backend.get(key);
            if (value == null) throw new IOException("REGISTRY_CHANGED");
            byte[] bytes = new byte[value.length() * 2];
            for (int i = 0; i < value.length(); i++) {
                bytes[2*i] = (byte)(value.charAt(i) >>> 8); bytes[2*i+1] = (byte)value.charAt(i);
            }
            result.put(key, bytes);
        }
        if (!backend.isAvailable()) throw new IOException(backend.unavailableReason());
        return result;
    }

    /** CREATE_NEW, force и точное обратное чтение предшествуют разрешению заменить corruption. */
    static Path preserve(Path memory, String store, String source, String reason, Map<String, byte[]> evidence) throws IOException {
        Path root = root(memory);
        if (evidence.isEmpty()) throw new IOException("NO_CORRUPTION_EVIDENCE");
        Path directory = root.resolve("Recovery");
        CanonicalPaths.requireNoLinks(directory);
        Files.createDirectories(directory);
        CanonicalPaths.requireNoLinks(directory);
        Path file = directory.resolve(store + "-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".md");
        StringBuilder text = new StringBuilder(Texts.get("session.quarantine.document", safe(source), safe(reason), Instant.now()));
        for (var entry : evidence.entrySet()) {
            byte[] bytes = entry.getValue();
            text.append("\n").append(Texts.get("session.quarantine.entry", safe(entry.getKey()), bytes.length, hash(bytes)))
                .append("\n```base64\n").append(Base64.getEncoder().encodeToString(bytes)).append("\n```\n");
            String preview = new String(bytes, store.equals("registry") ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8);
            if (preview.length() > 4096) preview = preview.substring(0, 4096);
            text.append(Texts.get("session.quarantine.preview")).append("\n```text\n").append(safe(preview)).append("\n```\n");
        }
        byte[] document = text.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(document);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        if (!Arrays.equals(document, Files.readAllBytes(file))) throw new IOException("QUARANTINE_READBACK");
        return file;
    }

    /** Повторная проверка исходных данных до первой мутации, без заявления interprocess CAS. */
    static void unchanged(Map<String, byte[]> before, Map<String, byte[]> after) throws IOException {
        if (!before.keySet().equals(after.keySet())) throw new IOException("SOURCE_CHANGED");
        for (String key : before.keySet()) if (!Arrays.equals(before.get(key), after.get(key))) throw new IOException("SOURCE_CHANGED");
    }

    /** Только папка CashMemory; проверяем существующие компоненты без ссылок. */
    private static Path root(Path memory) throws IOException {
        Path root = CanonicalPaths.requireNoLinks(memory.toAbsolutePath().normalize());
        if (!root.getFileName().toString().equals("CashMemory")) throw new IOException("CASHMEMORY_ROOT");
        return root;
    }

    /** Не допускает управляющий Markdown и запрещённые тире в диагностических метаданных. */
    private static String safe(String value) {
        StringBuilder text = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == 0x2013 || c == 0x2014 || c == 0x2212 || Character.isISOControl(c) || c == '`' || c == '<' || c == '>')
                text.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else text.append(c);
        }
        return text.toString();
    }

    /** SHA-256 точных исходных байтов. */
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
