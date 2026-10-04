package ru.cashprediction.core.session.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.json.JsonWriter;

/** Новые recovery regressions: настоящие files/HKCU и core recorder, без GUI. */
class CorruptStoreRecoveryTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.NEVER) Path home;

    /** Санитизация diagnostics не меняет точные исходные байты в Base64 payload. */
    @Test void quarantineEscapesNumericCodepointsWithoutLosingPayload() throws Exception {
        Path memory=Files.createDirectory(home.resolve("CashMemory"));
        String raw=new String(new int[]{0x2013,0x2014,0x2212,'`','<','>',0},0,7);
        byte[] original=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path archive=CorruptStoreQuarantine.preserve(memory,"xml",raw,raw,Map.of(raw,original));
        String document=Files.readString(archive);
        for(int code:new int[]{0x2013,0x2014,0x2212}) {
            assertEquals(-1,document.indexOf(code));
            assertTrue(document.contains(String.format(Locale.ROOT,"\\u%04x",code)));
        }
        assertTrue(document.contains(Base64.getEncoder().encodeToString(original)));
    }

    /** Частичный reset после archive не выдаётся за intact source или успешную новую запись. */
    @Test void registryResetFaultKeepsArchiveAndReportsTypedFailure() throws Exception {
        Path memory=Files.createDirectory(home.resolve("CashMemory")); var data=new InMemoryRegistryBackend();
        var registry=new RegistrySessionStore(data,"web",memory.toString()); registry.save(snapshot(0));
        data.put(RegistrySessionStore.KEY_SNAPSHOT_CRC,"00000000"); var original=inventory(data);
        RegistryBackend fault=new RegistryBackend(){
            /** Читает реальный store backend. */ public String get(String k){return data.get(k);}
            /** Делегирует запись. */ public void put(String k,String v){data.put(k,v);}
            /** Fault после настоящей первой мутации. */ public void remove(String k){data.remove(k);throw new IllegalStateException("reset fault");}
            /** Перечисляет ключи. */ public List<String> keys(){return data.keys();}
            /** Сбрасывает backend. */ public void flush() throws SessionStoreException {data.flush();}
            /** Доступность. */ public boolean isAvailable(){return data.isAvailable();}
            /** Причина. */ public String unavailableReason(){return data.unavailableReason();}
        };
        var recovering=new RegistrySessionStore(fault,"web",memory.toString());
        var failure=assertThrows(SessionStoreException.class,()->recovering.save(snapshot(1)));
        assertEquals(SessionStoreException.Code.IO_ERROR,failure.code()); assertTrue(failure.getMessage().contains("reset fault"));
        assertTrue(recovering.recoveryNotice().isEmpty());
        String archive=Files.readString(archives(memory).getFirst());
        for(var entry:original.entrySet()) assertTrue(archive.contains(Base64.getEncoder().encodeToString(utf16(entry.getValue()))));
        assertNotEquals(original,inventory(data));
    }

    /** Archive подтверждён, но locked XML commit не удался: не сообщаем успех до retry. */
    @Test void archiveThenCommitFailureIsHonestAndRetryRecovers() throws Exception {
        Path memory=Files.createDirectory(home.resolve("CashMemory"));
        var xml=XmlSessionStore.inCashMemory(memory,"web"); byte[] corrupt="<broken>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(xml.file(),corrupt);
        try(var locked=java.nio.channels.FileChannel.open(xml.file(), StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
            var failure=assertThrows(SessionStoreException.class,()->xml.save(snapshot(1)));
            assertEquals(SessionStoreException.Code.IO_ERROR,failure.code());
            assertTrue(xml.recoveryNotice().isEmpty()); assertTrue(xml.lastError().isPresent());
            assertArrayEquals(corrupt,Files.readAllBytes(xml.file())); assertTrue(containsBytes(archives(memory).getFirst(),corrupt));
        }
        xml.save(snapshot(2)); assertEquals(snapshot(2),xml.load().orElseThrow());
        assertTrue(xml.recoveryNotice().isPresent()); assertEquals(2,archives(memory).size());
    }

    /** Второе независимое повреждение не заменяет прежний archive. */
    @Test void repeatedCorruptionKeepsBothUniqueArchives() throws Exception {
        Path memory=Files.createDirectory(home.resolve("CashMemory")); var xml=XmlSessionStore.inCashMemory(memory,"web");
        Files.writeString(xml.file(),"<first-broken>"); xml.save(snapshot(1));
        var first=archives(memory).getFirst(); byte[] retained=Files.readAllBytes(first);
        Files.writeString(xml.file(),"<second-broken>"); xml.save(snapshot(2));
        assertEquals(2,archives(memory).size()); assertArrayEquals(retained,Files.readAllBytes(first));
        assertEquals(snapshot(2),xml.load().orElseThrow());
    }

    /** Даже валидные base64 данные не разрешают карантин вне CashMemory. */
    @Test void nonCashMemoryRecoveryIsRejected() throws Exception {
        var xml=new XmlSessionStore(home.resolve("session-web.xml"),"web"); Files.writeString(xml.file(),"<broken>");
        var failure=assertThrows(SessionStoreException.class,()->xml.save(snapshot(1)));
        assertEquals(SessionStoreException.Code.IO_ERROR,failure.code()); assertEquals("<broken>",Files.readString(xml.file()));
        assertFalse(Files.exists(home.resolve("Recovery")));
    }

    /** Все stores corrupt: архив до замены, затем два свежих capture с invalid fields. */
    @Test void allCorruptThenFreshInvalidCaptureWithActualHkcu() throws Exception {
        String node = System.getProperty("cashprediction.req113.registryNode", "");
        if(node.isBlank()) node="ru/cashprediction/selftest/"+UUID.randomUUID();
        assertTrue(System.getProperty("os.name").startsWith("Windows"));
        assertTrue(node.matches("ru/cashprediction/selftest/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
        Map<String,Object> receipt = new LinkedHashMap<>();
        SessionRecorder recorder = null;
        try {
            assertFalse(Preferences.userRoot().nodeExists(node));
            Path memory = Files.createDirectory(home.resolve("CashMemory"));
            var backend = new PreferencesRegistryBackend(node);
            assertTrue(backend.isAvailable());
            var registry = new RegistrySessionStore(backend, "web", memory.toString());
            var xml = XmlSessionStore.inCashMemory(memory, "web");
            var md = MarkdownSessionStore.inCashMemory(memory);
            registry.save(snapshot(0)); backend.put(RegistrySessionStore.KEY_SNAPSHOT_CRC, "00000000"); backend.flush();
            Map<String,String> rawRegistry = inventory(backend);
            byte[] rawXml = "<broken>original XML sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] rawMd = "# broken session original MD sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(xml.file(), rawXml); Files.write(md.sessionFile(), rawMd);
            var source = new Source(snapshot(1));
            var window = new Window(source.snapshot.windows().getFirst());
            Map<String,StoreStatus> latest = new LinkedHashMap<>();
            recorder = new SessionRecorder("web", List.of(xml, md, registry), UiExecutor.direct(), source,
                    new Timers(), Clock.fixed(Instant.ofEpochSecond(20), ZoneOffset.UTC));
            recorder.addStatusListener(status -> latest.put(status.storeId(), status));
            recorder.start(); recorder.register(window); recorder.saveNow();
            for (var store : recorder.stores()) {
                assertTrue(latest.get(store.id()).ok(), latest.get(store.id()).message());
                assertFalse(notice(store).isBlank());
                assertEquals(source.snapshot.windows(), store.load().orElseThrow().windows());
                assertEquals(source.snapshot.plan(), store.load().orElseThrow().plan());
            }
            List<Path> archives = archives(memory); assertEquals(3, archives.size());
            assertTrue(archives.stream().anyMatch(path -> containsBytes(path, rawXml)));
            assertTrue(archives.stream().anyMatch(path -> containsBytes(path, rawMd)));
            Path registryArchive = archives.stream().filter(path -> path.getFileName().toString().startsWith("registry-")).findFirst().orElseThrow();
            String registryText = Files.readString(registryArchive);
            for (var entry : rawRegistry.entrySet()) {
                assertTrue(registryText.contains(entry.getKey()));
                assertTrue(registryText.contains(Base64.getEncoder().encodeToString(utf16(entry.getValue()))));
            }
            source.snapshot = snapshot(2); window.state = source.snapshot.windows().getFirst(); recorder.saveNow();
            for (var store : recorder.stores()) assertEquals(source.snapshot.windows(), store.load().orElseThrow().windows());
            assertEquals(archives, archives(memory));
            receipt.put("originalRegistry", rawRegistry); receipt.put("quarantines", archives.stream().map(Path::toString).toList());
            receipt.put("storeRecoveryNotices", List.of(notice(xml),notice(md),notice(registry))); receipt.put("freshInvalidFields", source.snapshot.windows().getFirst().fields());
            receipt.put("scope", "ACTUAL_ROOT_HKCU_FILES_RECORDER_CAPTURE_STORE_NOTICES_OBSERVER_INTEGRATION_PENDING_NO_GUI_NATIVE");
        } finally {
            try { if (recorder != null) recorder.shutdownClean(); }
            finally {
                try {
                    if (Preferences.userRoot().nodeExists(node)) {
                        var prefs = Preferences.userRoot().node(node); var parent = prefs.parent(); prefs.removeNode(); parent.flush();
                    }
                } finally {
                    receipt.put("node", node); receipt.put("afterExists", Preferences.userRoot().nodeExists(node));
                    String supplied=System.getProperty("cashprediction.req113.receipts","");
                    Path receipts=Files.createDirectories(supplied.isBlank()?home.resolve("receipts"):Path.of(supplied));
                    Files.writeString(receipts.resolve("actual-recovery.json"), JsonWriter.write(receipt));
                    assertFalse(Preferences.userRoot().nodeExists(node));
                }
            }
        }
    }

    /** Recovery одного store не вызывает clear/update исправного соседа. */
    @Test void healthyXmlRemainsByteIdenticalWhileOtherStoresRecover() throws Exception {
        Path memory = Files.createDirectory(home.resolve("CashMemory"));
        var xml = XmlSessionStore.inCashMemory(memory, "web"); xml.save(snapshot(0)); byte[] healthy = Files.readAllBytes(xml.file());
        var backend = new InMemoryRegistryBackend(); var registry = new RegistrySessionStore(backend, "web", memory.toString());
        registry.save(snapshot(0)); backend.put(RegistrySessionStore.KEY_SNAPSHOT_CRC, "00000000");
        var md = MarkdownSessionStore.inCashMemory(memory); Files.writeString(md.sessionFile(), "broken");
        md.save(snapshot(1)); registry.save(snapshot(1));
        assertArrayEquals(healthy, Files.readAllBytes(xml.file())); assertEquals(snapshot(0), xml.load().orElseThrow());
        assertEquals(2, archives(memory).size());
    }

    /** Отказ создания карантина оставляет исходный store intact, новый snapshot не заявляется. */
    @ParameterizedTest @ValueSource(strings={"xml","server","registry"})
    void quarantineFailureLeavesCorruptEvidenceUntouched(String kind) throws Exception {
        Path memory = Files.createDirectory(home.resolve("CashMemory"));
        Files.writeString(memory.resolve("Recovery"), "not a directory");
        var backend = new InMemoryRegistryBackend(); var registry = new RegistrySessionStore(backend, "web", memory.toString());
        registry.save(snapshot(0)); backend.put(RegistrySessionStore.KEY_SNAPSHOT_CRC, "00000000");
        var xml = XmlSessionStore.inCashMemory(memory, "web"); Files.writeString(xml.file(), "<broken>");
        var md = MarkdownSessionStore.inCashMemory(memory); Files.writeString(md.sessionFile(), "broken");
        Map<String,String> originalRegistry = inventory(backend); byte[] originalXml = Files.readAllBytes(xml.file()); byte[] originalMd = Files.readAllBytes(md.sessionFile());
        SessionStore store = switch(kind) {case "xml" -> xml; case "server" -> md; default -> registry;};
        var error = assertThrows(SessionStoreException.class, () -> store.save(snapshot(1)));
        assertEquals(SessionStoreException.Code.IO_ERROR, error.code());
        assertEquals(originalRegistry, inventory(backend)); assertArrayEquals(originalXml, Files.readAllBytes(xml.file())); assertArrayEquals(originalMd, Files.readAllBytes(md.sessionFile()));
        assertTrue(notice(store).isEmpty());
    }

    /** Machine IO_ERROR не становится CORRUPT: quarantine/overwrite не запускаются. */
    @Test void ioErrorIsNotEligibleForQuarantine() throws Exception {
        Path memory = Files.createDirectory(home.resolve("CashMemory"));
        var xml = XmlSessionStore.inCashMemory(memory, "web"); Files.createDirectory(xml.file());
        var failure = assertThrows(SessionStoreException.class, () -> xml.save(snapshot(1)));
        assertEquals(SessionStoreException.Code.IO_ERROR, failure.code());
        assertFalse(Files.exists(memory.resolve("Recovery"))); assertTrue(Files.isDirectory(xml.file()));
    }

    /** Broken journal и sidecar сохраняются в одном archive с точными исходными байтами. */
    @Test void markdownArchiveIncludesBrokenJournalAndSidecar() throws Exception {
        Path memory = Files.createDirectory(home.resolve("CashMemory")); var md = MarkdownSessionStore.inCashMemory(memory);
        md.save(snapshot(0)); Path journal = md.sessionFile().resolveSibling("web-session.md.transaction.md");
        byte[] rawSidecar = Files.readAllBytes(md.planFile()); byte[] rawSession = Files.readAllBytes(md.sessionFile());
        byte[] rawJournal = "broken journal".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(journal, rawJournal);
        md.save(snapshot(1)); assertEquals(snapshot(1), md.load().orElseThrow());
        Path archive = archives(memory).getFirst(); assertTrue(containsBytes(archive, rawSidecar)); assertTrue(containsBytes(archive, rawSession)); assertTrue(containsBytes(archive, rawJournal));
        assertFalse(Files.exists(journal));
    }

    /** Concrete API до согласованного MAIN расширения SessionStore/Recorder/StatusBuilder. */
    private static String notice(SessionStore store) {
        if(store instanceof XmlSessionStore xml) return xml.recoveryNotice().orElse("");
        if(store instanceof MarkdownSessionStore md) return md.recoveryNotice().orElse("");
        if(store instanceof RegistrySessionStore registry) return registry.recoveryNotice().orElse("");
        throw new AssertionError("unexpected store");
    }
    /** Новые invalid текстовые значения остаются raw, без нормализации. */
    private static SessionSnapshot snapshot(int version) {
        var window = new WindowState("invalid", WindowType.ONE_TIME_EDITOR, true, "main", new WindowBounds(10,20,300,400),
                Map.of("mode","create"), Map.of("amount", "1,234v"+version, "date", "31.02.2031v"+version));
        return SessionSnapshot.of(Instant.ofEpochSecond(version), "web", MainWindowState.empty(), PlanState.dirty("new raw plan "+version), List.of(window));
    }
    /** Собирает логическую опись значений до замены. */
    private static Map<String,String> inventory(RegistryBackend backend) { var map = new TreeMap<String,String>(); for (String key : backend.keys()) map.put(key, backend.get(key)); return map; }
    /** Список retained quarantine paths. */
    private static List<Path> archives(Path memory) throws Exception { try(var paths=Files.list(memory.resolve("Recovery"))) { return paths.sorted().toList(); } }
    /** Проверяет наличие exact binary payload в диагностическом Markdown. */
    private static boolean containsBytes(Path file, byte[] bytes) { try { return Files.readString(file).contains(Base64.getEncoder().encodeToString(bytes)); } catch(Exception e) { throw new AssertionError(e); } }
    /** UTF-16BE code units, включая непарные суррогаты. */
    private static byte[] utf16(String value) { byte[] bytes=new byte[value.length()*2]; for(int i=0;i<value.length();i++){bytes[2*i]=(byte)(value.charAt(i)>>>8);bytes[2*i+1]=(byte)value.charAt(i);} return bytes; }
    /** Только UI capture boundary заменён тестом. */
    private static final class Source implements SnapshotSource {
        SessionSnapshot snapshot; Source(SessionSnapshot snapshot) { this.snapshot=snapshot; }
        /** Состояние main для production recorder. */
        @Override public MainWindowState captureMain(){return snapshot.main();}
        /** Несохранённый план для production recorder. */
        @Override public PlanState capturePlan(){return snapshot.plan();}
    }
    /** Только native окно заменено headless capture boundary. */
    private static final class Window implements StatefulWindow {
        WindowState state; Window(WindowState state){this.state=state;}
        /** Идентификатор окна. */ @Override public String windowId(){return state.id();}
        /** Тип окна. */ @Override public WindowType windowType(){return state.type();}
        /** Модальность. */ @Override public boolean modal(){return state.modal();}
        /** Владелец. */ @Override public String ownerId(){return state.ownerId();}
        /** Снимает invalid поля. */ @Override public WindowState captureState(){return state;}
        /** Применяет состояние. */ @Override public void applyState(WindowState state){this.state=state;}
    }
    /** Без фоновых таймеров, saveNow production. */
    private static final class Timers implements Scheduler {
        /** Debounce не запускается. */ @Override public Task schedule(Runnable r,Duration d){return ()->{};}
        /** Периодический таймер не запускается. */ @Override public Task scheduleAtFixedRate(Runnable r,Duration a,Duration p){return ()->{};}
        /** Явная запись исполняется. */ @Override public void execute(Runnable r){r.run();}
        /** Нет потоков для остановки. */ @Override public void shutdown(){}
    }
}
