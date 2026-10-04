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
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.RecorderStatus;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.status.*;
import ru.cashprediction.core.ui.view.table.ViewStates;

/** Дополнительные lifecycle и journal границы на actual files/HKCU, без повторения прежних 18 cases. */
class StoreLifecycleBoundaryTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.NEVER) Path home;

    /** Реальные recorder statuses доходят до StatusBuilder: failure не маскируется recovery warning. */
    @Test void observerWarnOnlyAfterNewCommitAndErrorWhenQuarantineFails() throws Exception {
        Path memory=Files.createDirectory(home.resolve("CashMemory"));
        var xml=XmlSessionStore.inCashMemory(memory,"web"); var md=MarkdownSessionStore.inCashMemory(memory);
        var fresh=StoreCommitFaultTest.snapshot("web","fresh observer plan",1);
        md.save(StoreCommitFaultTest.snapshot("web","healthy old plan",0));
        md.markDirty(SessionMarker.running(123,Instant.EPOCH,"web"));
        byte[] original="<broken>observer sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(xml.file(),original); Files.writeString(memory.resolve("Recovery"),"blocked quarantine");
        var statuses=new LinkedHashMap<String,StoreStatus>();
        var recorder=new SessionRecorder("web",List.of(xml,md),UiExecutor.direct(),new SnapshotSource(){
            /** Свежее состояние main. */ public MainWindowState captureMain(){return fresh.main();}
            /** Новый план. */ public PlanState capturePlan(){return fresh.plan();}
        },new Timers(),Clock.fixed(Instant.ofEpochSecond(60),ZoneOffset.UTC));
        recorder.addStatusListener(status -> statuses.put(status.storeId(),status));
        try {
            recorder.register(new Window(fresh.windows().getFirst())); recorder.start(); recorder.saveNow();
            assertFalse(statuses.get(xml.id()).ok()); assertTrue(xml.recoveryNotice().isEmpty());
            var failed=segment(List.copyOf(statuses.values()));
            assertEquals(ColorToken.EXPENSE,failed.color()); assertTrue(failed.tooltip().contains(statuses.get(xml.id()).message()));
            assertArrayEquals(original,Files.readAllBytes(xml.file()));
            assertEquals(fresh.windows(),md.load().orElseThrow().windows());
            Files.delete(memory.resolve("Recovery"));
            try(var locked=java.nio.channels.FileChannel.open(xml.file(),StandardOpenOption.READ,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
                recorder.saveNow();
                assertFalse(statuses.get(xml.id()).ok()); assertTrue(xml.recoveryNotice().isEmpty());
                assertEquals(ColorToken.EXPENSE,segment(List.copyOf(statuses.values())).color());
                assertArrayEquals(original,Files.readAllBytes(xml.file()));
            }
            List<Path> firstArchives;
            try(var paths=Files.list(memory.resolve("Recovery"))){firstArchives=paths.toList();}
            assertEquals(1,firstArchives.size()); byte[] first=Files.readAllBytes(firstArchives.getFirst());
            assertTrue(Files.readString(firstArchives.getFirst()).contains(Base64.getEncoder().encodeToString(original)));
            recorder.saveNow();
            StoreStatus committed=statuses.get(xml.id()); assertTrue(committed.ok());
            assertEquals(xml.recoveryNotice().orElseThrow(),committed.message());
            assertEquals(fresh.windows(),xml.load().orElseThrow().windows());
            var warning=segment(List.copyOf(statuses.values()));
            assertEquals(ColorToken.WARN,warning.color()); assertTrue(warning.tooltip().contains(committed.message()));
            assertTrue(statuses.get(md.id()).ok()); assertEquals("",statuses.get(md.id()).message());
            recorder.shutdownClean();
            assertEquals(ColorToken.WARN,segment(List.copyOf(statuses.values())).color());
            assertFalse(xml.readMarker().orElseThrow().isRunning());
            assertArrayEquals(first,Files.readAllBytes(firstArchives.getFirst()));
        } finally {recorder.shutdownClean();}
    }

    /** Использует actual StatusBuilder с настоящими listener statuses, не имитирует observer сообщения. */
    private static StatusSegment segment(List<StoreStatus> statuses) {
        AppState base=ViewStates.sample();
        var state=new AppState(base.revision(),base.profile(),base.today(),base.cashMemory(),base.plansFolder(),
                base.document(),base.view(),base.selectedRowId(),base.pastExpanded(),base.settings(),
                RecorderStatus.RECORDING,statuses,base.windows(),base.status(),base.autosaveProblem());
        return StatusBuilder.build(state,Instant.ofEpochSecond(60),ZoneOffset.UTC).find(StatusModel.SESSION).orElseThrow();
    }

    /** CORRUPT архивируется, IO/UNAVAILABLE не архивируются, healthy store сохраняет свежий сеанс и clean marker. */
    @ParameterizedTest @ValueSource(booleans={false,true})
    void mixedStoresAcrossStartSaveAndCleanClose(boolean unavailable) throws Exception {
        String node=ownedNode(); SessionRecorder recorder=null;
        Map<String,Object> receipt=new LinkedHashMap<>();
        try {
            assertFalse(Preferences.userRoot().nodeExists(node));
            Path memory=Files.createDirectory(home.resolve("CashMemory"));
            var actual=new PreferencesRegistryBackend(node); assertTrue(actual.isAvailable());
            var backend=new BoundaryBackend(actual);
            var registry=new RegistrySessionStore(backend,"web",memory.toString());
            var xml=XmlSessionStore.inCashMemory(memory,"web");
            var md=MarkdownSessionStore.inCashMemory(memory);
            var old=StoreCommitFaultTest.snapshot("web","old plan",0);
            var fresh=StoreCommitFaultTest.snapshot("web","new invalid plan",1);
            registry.save(old);
            byte[] corrupt="broken original MD\r\n\u0000sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            SessionStore healthy;
            Map<String,String> original;
            if(unavailable) {
                xml.save(old); healthy=xml;
                Files.write(md.sessionFile(),corrupt);
                original=inventory(actual); backend.unavailable=true;
                assertEquals(SessionStoreException.Code.UNAVAILABLE,
                        assertThrows(SessionStoreException.class,registry::load).code());
            } else {
                md.save(old); healthy=md;
                Files.createDirectory(xml.file()); Files.writeString(xml.file().resolve("sentinel"),"retained IO evidence");
                actual.put(RegistrySessionStore.KEY_SNAPSHOT_CRC,"00000000"); actual.flush(); original=inventory(actual);
                assertEquals(SessionStoreException.Code.IO_ERROR,
                        assertThrows(SessionStoreException.class,xml::load).code());
                assertEquals(SessionStoreException.Code.CORRUPT,
                        assertThrows(SessionStoreException.class,registry::load).code());
            }
            healthy.markDirty(SessionMarker.running(123,Instant.EPOCH,"web"));
            var statuses=new LinkedHashMap<String,StoreStatus>();
            recorder=new SessionRecorder("web",List.of(xml,md,registry),UiExecutor.direct(),new SnapshotSource(){
                /** Снимает main из свежего снимка. */ public MainWindowState captureMain(){return fresh.main();}
                /** Снимает свежий план. */ public PlanState capturePlan(){return fresh.plan();}
            },new Timers(),Clock.fixed(Instant.ofEpochSecond(50),ZoneOffset.UTC));
            recorder.addStatusListener(status -> statuses.put(status.storeId(),status));
            recorder.register(new Window(fresh.windows().getFirst()));
            recorder.start();
            assertEquals(old,healthy.load().orElseThrow());
            assertEquals(original,inventory(actual));
            if(unavailable) assertArrayEquals(corrupt,Files.readAllBytes(md.sessionFile()));
            assertFalse(Files.exists(memory.resolve("Recovery")));
            recorder.saveNow();
            SessionStore recovered=unavailable?md:registry;
            for(var store:List.of(healthy,recovered)) {
                var loaded=store.load().orElseThrow();
                assertEquals(fresh.windows(),loaded.windows()); assertEquals(fresh.plan(),loaded.plan());
                assertTrue(statuses.get(store.id()).ok());
            }
            assertFalse(statuses.get(unavailable?registry.id():xml.id()).ok());
            List<Path> archives;
            try(var paths=Files.list(memory.resolve("Recovery"))){archives=paths.toList();}
            assertEquals(1,archives.size()); Path archive=archives.getFirst(); byte[] retained=Files.readAllBytes(archive);
            String text=Files.readString(archive);
            if(unavailable) {
                assertTrue(text.contains(Base64.getEncoder().encodeToString(corrupt)));
                assertEquals(original,inventory(actual));
            } else {
                for(var entry:original.entrySet()) assertTrue(text.contains(Base64.getEncoder().encodeToString(utf16(entry.getValue()))));
                assertEquals("retained IO evidence",Files.readString(xml.file().resolve("sentinel")));
            }
            recorder.shutdownClean(); recorder=null;
            for(var store:List.of(healthy,recovered)) {
                assertEquals(fresh.windows(),store.load().orElseThrow().windows());
                assertEquals(fresh.plan(),store.load().orElseThrow().plan());
                assertFalse(store.readMarker().orElseThrow().isRunning());
            }
            assertArrayEquals(retained,Files.readAllBytes(archive));
            try(var paths=Files.list(memory.resolve("Recovery"))){assertEquals(1,paths.count());}
            if(unavailable) {
                assertEquals(original,inventory(actual)); backend.unavailable=false;
                assertEquals(old,registry.load().orElseThrow());
                assertTrue(registry.recoveryNotice().isEmpty());
            } else {
                assertEquals(SessionStoreException.Code.IO_ERROR,assertThrows(SessionStoreException.class,xml::load).code());
                assertTrue(xml.recoveryNotice().isEmpty()); assertTrue(xml.lastError().isPresent());
                assertEquals("retained IO evidence",Files.readString(xml.file().resolve("sentinel")));
            }
            receipt.put("scenario",unavailable?"MD_CORRUPT_REGISTRY_UNAVAILABLE_XML_HEALTHY":"REGISTRY_CORRUPT_XML_IO_MD_HEALTHY");
            receipt.put("archive",archive.toString()); receipt.put("originalRegistry",original);
            receipt.put("freshFields",fresh.windows().getFirst().fields()); receipt.put("cleanCloseVerified",true);
        } finally {
            try {if(recorder!=null)recorder.shutdownClean();}
            finally {cleanup(node,receipt,"mixed-"+unavailable+".json");}
        }
    }

    /** Pending уже flushed, primary ещё не тронут: reopening даёт old и retry завершает transaction без quarantine. */
    @Test void actualHkcuCrashAfterPendingFlushBeforePrimaryMutation() throws Exception {
        String node=ownedNode(); Map<String,Object> receipt=new LinkedHashMap<>();
        try {
            assertFalse(Preferences.userRoot().nodeExists(node));
            Path memory=Files.createDirectory(home.resolve("CashMemory"));
            var actual=new PreferencesRegistryBackend(node); assertTrue(actual.isAvailable());
            var backend=new BoundaryBackend(actual);
            var store=new RegistrySessionStore(backend,"web",memory.toString());
            var old=StoreCommitFaultTest.snapshot("web","old-".repeat(2500),0);
            var fresh=StoreCommitFaultTest.snapshot("web","new-".repeat(2500),1);
            store.save(old); var original=inventory(actual); backend.pendingFlushFault=true;
            var failure=assertThrows(SessionStoreException.class,()->store.save(fresh));
            assertEquals(SessionStoreException.Code.IO_ERROR,failure.code());
            assertEquals("1",actual.get("transaction.pending")); assertTrue(backend.boundaryReached);
            for(var entry:original.entrySet()) assertEquals(entry.getValue(),actual.get(entry.getKey()));
            assertTrue(actual.keys().stream().anyMatch(key->key.startsWith("backup.snapshot.")));
            var reopened=new RegistrySessionStore(new PreferencesRegistryBackend(node),"web",memory.toString());
            assertEquals(old,reopened.load().orElseThrow()); assertEquals(old.savedAt(),reopened.lastSavedAt().orElseThrow());
            assertFalse(Files.exists(memory.resolve("Recovery")));
            backend.pendingFlushFault=false; store.save(fresh);
            assertEquals(fresh,reopened.load().orElseThrow());
            assertFalse(actual.keys().stream().anyMatch(key->key.startsWith("backup.")||key.equals("transaction.pending")));
            receipt.put("boundary","ACTUAL_HKCU_AFTER_PENDING_FLUSH_BEFORE_PRIMARY_MUTATION");
            receipt.put("originalRegistry",original); receipt.put("oldReopenedThenFreshRetry",true);
        } finally {cleanup(node,receipt,"pending-flush.json");}
    }

    /** Проверяет exact selftest UUID до любого доступа к Preferences. */
    private static String ownedNode(){String node=System.getProperty("cashprediction.req113.registryNode","");
        if(node.isBlank()) node="ru/cashprediction/selftest/"+UUID.randomUUID();
        assertTrue(System.getProperty("os.name").startsWith("Windows"));
        assertTrue(node.matches("ru/cashprediction/selftest/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"));return node;}
    /** Удаляет только свой UUID и сохраняет независимый Java receipt даже после assertion failure. */
    private void cleanup(String node,Map<String,Object> receipt,String name) throws Exception {
        try {if(Preferences.userRoot().nodeExists(node)){var prefs=Preferences.userRoot().node(node);var parent=prefs.parent();prefs.removeNode();parent.flush();}}
        finally {receipt.put("node",node);receipt.put("afterExists",Preferences.userRoot().nodeExists(node));
            receipt.put("scope","ROOT_HEADLESS_ACTUAL_FILES_HKCU_CONTROLLED_BACKEND_FAULT_NOT_OS_POWERLOSS_GUI_NATIVE");
            String supplied=System.getProperty("cashprediction.req113.receipts","");
            Path receipts=Files.createDirectories(supplied.isBlank()?home.resolve("receipts"):Path.of(supplied));
            Files.writeString(receipts.resolve(name),JsonWriter.write(receipt));
            assertFalse(Preferences.userRoot().nodeExists(node));}
    }
    /** Логические значения actual node. */
    private static Map<String,String> inventory(RegistryBackend backend){var values=new TreeMap<String,String>();for(String key:backend.keys())values.put(key,backend.get(key));return values;}
    /** Exact UTF-16 code units для доказательства сохранения registry payload. */
    private static byte[] utf16(String text){byte[] bytes=new byte[2*text.length()];for(int i=0;i<text.length();i++){bytes[2*i]=(byte)(text.charAt(i)>>>8);bytes[2*i+1]=(byte)text.charAt(i);}return bytes;}
    /** Сохраняет native backend, внедряет только availability или ошибку после actual pending flush. */
    private static final class BoundaryBackend implements RegistryBackend {
        private final RegistryBackend actual; boolean unavailable,pendingFlushFault,boundaryReached;
        /** Делегируемый actual backend. */ BoundaryBackend(RegistryBackend actual){this.actual=actual;}
        /** Читает без hand-model данных. */ public String get(String key){return actual.get(key);}
        /** Делегирует put. */ public void put(String key,String value){actual.put(key,value);}
        /** Делегирует remove. */ public void remove(String key){actual.remove(key);}
        /** Делегирует keys. */ public List<String> keys(){return actual.keys();}
        /** После реального flush pending прерывает save до primary mutation. */ public void flush() throws SessionStoreException {
            actual.flush();if(pendingFlushFault&&"1".equals(actual.get("transaction.pending"))){boundaryReached=true;
                throw new SessionStoreException(SessionStoreException.Code.IO_ERROR,"injected after durable pending flush");}}
        /** Не классифицирует unavailable как corruption. */ public boolean isAvailable(){return !unavailable&&actual.isAvailable();}
        /** Причина контролируемого отказа. */ public String unavailableReason(){return unavailable?"injected unavailable":actual.unavailableReason();}
    }
    /** Headless окно сохраняет raw invalid fields production recorder. */
    private record Window(WindowState state) implements StatefulWindow {
        /** Идентификатор. */ public String windowId(){return state.id();}
        /** Тип. */ public WindowType windowType(){return state.type();}
        /** Модальность. */ public boolean modal(){return state.modal();}
        /** Владелец. */ public String ownerId(){return state.ownerId();}
        /** Capture. */ public WindowState captureState(){return state;}
        /** Restore вне проверяемого lifecycle. */ public void applyState(WindowState ignored){}
    }
    /** Таймеры не исполняются; проверяются явные production lifecycle calls. */
    private static final class Timers implements Scheduler {
        /** Без debounce. */ public Task schedule(Runnable action,Duration delay){return ()->{};}
        /** Без periodic. */ public Task scheduleAtFixedRate(Runnable action,Duration initial,Duration period){return ()->{};}
        /** Синхронная запись. */ public void execute(Runnable action){action.run();}
        /** Нет потоков. */ public void shutdown(){}
    }
}
