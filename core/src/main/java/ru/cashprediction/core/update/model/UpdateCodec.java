package ru.cashprediction.core.update.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;

/** Строгий кодек схемы 2 и общих записей ZIP без внешних библиотек. */
public final class UpdateCodec {
    private UpdateCodec() { }

    /** Читает полностью проверенный манифест; неверные входы дают IOException. */
    public static UpdateManifest read(String json) throws IOException {
        try {
            Map<String, Object> m = object(parse(json));
            keys(m, "schemaVersion", "releaseNumber", "commitSha", "version", "publishedAtUtc",
                    "assetName", "sizeBytes", "sha256", "treeSha256", "files", "deltaPatches");
            require(number(m, "schemaVersion") == 2, "SCHEMA");
            List<DeltaPatch> deltas = new ArrayList<>();
            for (Object d : array(m.get("deltaPatches"))) deltas.add(readDelta(object(d)));
            UpdateManifest result = new UpdateManifest(integer(m, "releaseNumber"), string(m, "commitSha"),
                    string(m, "version"), Instant.parse(string(m, "publishedAtUtc")), string(m, "assetName"),
                    number(m, "sizeBytes"), string(m, "sha256"), string(m, "treeSha256"),
                    readFiles(m.get("files")), deltas);
            UpdateValidation.manifest(result);
            return result;
        } catch (RuntimeException e) {
            throw new IOException("INVALID_MANIFEST", e);
        }
    }

    /** Записывает проверенный манифест в фиксированном порядке ключей. */
    public static String write(UpdateManifest m) throws IOException {
        UpdateValidation.manifest(m);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("schemaVersion", 2);
        o.put("releaseNumber", m.releaseNumber());
        o.put("commitSha", m.commitSha());
        o.put("version", m.version());
        o.put("publishedAtUtc", m.publishedAtUtc().toString());
        o.put("assetName", m.assetName());
        o.put("sizeBytes", m.sizeBytes());
        o.put("sha256", m.sha256());
        o.put("treeSha256", m.treeSha256());
        o.put("files", fileObjects(m.files()));
        List<Object> ds = new ArrayList<>();
        for (DeltaPatch d : m.deltaPatches()) ds.add(deltaObject(d));
        o.put("deltaPatches", ds);
        String result = JsonWriter.write(o);
        bounded(result);
        return result;
    }

    /** Разбирает JSON с ограничением размера и корректности Unicode. */
    public static Object parse(String json) throws IOException {
        bounded(json);
        unicodeEscapes(json);
        try { return JsonParser.parse(json); }
        catch (RuntimeException e) { throw new IOException("INVALID_JSON", e); }
    }

    /** Требует объект со строковыми ключами. */
    public static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException("OBJECT_REQUIRED");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) throw new IOException("KEY_REQUIRED");
            result.put(key, e.getValue());
        }
        return result;
    }

    /** Требует точный набор полей без неизвестных или отсутствующих ключей. */
    public static void keys(Map<String, Object> m, String... keys) throws IOException {
        if (!m.keySet().equals(Set.of(keys))) throw new IOException("OBJECT_FIELDS");
    }

    /** Читает обязательную строку с корректными суррогатами. */
    public static String string(Map<String, Object> m, String key) throws IOException {
        if (!(m.get(key) instanceof String s)) throw new IOException("STRING_REQUIRED");
        UpdateValidation.unicode(s);
        return s;
    }

    /** Читает целое число long без дробных и экспоненциальных подстановок. */
    public static long number(Map<String, Object> m, String key) throws IOException {
        if (!(m.get(key) instanceof Long n)) throw new IOException("INTEGER_REQUIRED");
        return n;
    }

    /** Читает целое число в диапазоне int. */
    public static int integer(Map<String, Object> m, String key) throws IOException {
        long n = number(m, key);
        require(n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE, "INTEGER_RANGE");
        return (int) n;
    }

    /** Читает массив с ограничением числа элементов. */
    public static List<?> array(Object value) throws IOException {
        if (!(value instanceof List<?> list) || list.size() > UpdateValidation.MAX_FILES)
            throw new IOException("ARRAY_REQUIRED");
        return list;
    }

    /** Читает строго отсортированный инвентарь файлов. */
    public static List<FileEntry> readFiles(Object value) throws IOException {
        List<FileEntry> result = new ArrayList<>();
        for (Object item : array(value)) {
            Map<String, Object> m = object(item);
            keys(m, "path", "sizeBytes", "sha256", "readOnly");
            if (!(m.get("readOnly") instanceof Boolean ro)) throw new IOException("BOOLEAN_REQUIRED");
            result.add(new FileEntry(string(m, "path"), number(m, "sizeBytes"), string(m, "sha256"), ro));
        }
        UpdateValidation.files(result, true);
        return List.copyOf(result);
    }

    /** Преобразует проверенные записи файлов в JSON-объекты. */
    public static List<Object> fileObjects(List<FileEntry> files) throws IOException {
        UpdateValidation.files(files, true);
        List<Object> result = new ArrayList<>();
        for (FileEntry f : files) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", f.path()); m.put("sizeBytes", f.sizeBytes());
            m.put("sha256", f.sha256()); m.put("readOnly", f.readOnly());
            result.add(m);
        }
        return result;
    }

    /** Читает дескриптор с явным алгоритмом и версией. */
    public static DeltaPatch readDelta(Map<String, Object> m) throws IOException {
        keys(m, "algorithm", "algorithmVersion", "baseReleaseNumber", "baseCommitSha",
                "baseTreeSha256", "assetName", "sizeBytes", "sha256");
        algorithm(m);
        DeltaPatch d = new DeltaPatch(integer(m, "baseReleaseNumber"), string(m, "baseCommitSha"),
                string(m, "baseTreeSha256"), string(m, "assetName"), number(m, "sizeBytes"), string(m, "sha256"));
        UpdateValidation.delta(d);
        return d;
    }

    /** Проверяет название и версию алгоритма контейнера. */
    public static void algorithm(Map<String, Object> m) throws IOException {
        require(UpdateValidation.ALGORITHM.equals(string(m, "algorithm"))
                && number(m, "algorithmVersion") == 1, "ALGORITHM");
    }

    /** Прерывает обработку с техническим кодом при нарушении условия. */
    public static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }

    private static Map<String, Object> deltaObject(DeltaPatch d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("algorithm", UpdateValidation.ALGORITHM); m.put("algorithmVersion", 1);
        m.put("baseReleaseNumber", d.baseReleaseNumber()); m.put("baseCommitSha", d.baseCommitSha());
        m.put("baseTreeSha256", d.baseTreeSha256()); m.put("assetName", d.assetName());
        m.put("sizeBytes", d.sizeBytes()); m.put("sha256", d.sha256());
        return m;
    }

    private static void bounded(String json) throws IOException {
        UpdateValidation.unicode(json);
        if (json.length() > UpdateValidation.MAX_JSON
                || json.getBytes(StandardCharsets.UTF_8).length > UpdateValidation.MAX_JSON)
            throw new IOException("JSON_SIZE");
    }

    // Общий JSON parser понимает Character.digit; wire JSON допускает только
    // ASCII hex в escape, а не визуально похожие цифры из других алфавитов.
    private static void unicodeEscapes(String json) throws IOException {
        for (int i = 0; i < json.length(); i++) {
            if (json.charAt(i) != '\\') continue;
            if (++i >= json.length()) throw new IOException("INVALID_JSON_ESCAPE");
            if (json.charAt(i) != 'u') continue;
            if (i + 4 >= json.length()) throw new IOException("INVALID_JSON_ESCAPE");
            for (int j = 1; j <= 4; j++) {
                char c = json.charAt(i + j);
                if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'))
                    throw new IOException("INVALID_JSON_ESCAPE");
            }
            i += 4;
        }
    }
}
