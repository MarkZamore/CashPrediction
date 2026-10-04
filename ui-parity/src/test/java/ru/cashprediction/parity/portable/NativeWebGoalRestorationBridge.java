package ru.cashprediction.parity.portable;

import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.parity.browser.*;
import ru.cashprediction.parity.driver.*;

/** Отдельный actual DOM/CDP worker для post-transaction GOAL probe; не запускает native сервер. */
public final class NativeWebGoalRestorationBridge {
    private NativeWebGoalRestorationBridge() { }

    /** Проверяет адрес до создания транспорта; token/path/origin должны совпадать с native handshake. */
    public static URI requireAddress(String address) {
        if (!address.matches("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/\\?t=[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("WEB_GOAL_ADDRESS");
        URI uri=URI.create(address);
        if(uri.getPort()>65535) throw new IllegalArgumentException("WEB_GOAL_PORT");
        TestApiBridge.http(uri); // Только создание sender: никакого HTTP до actual DOM step.
        return uri;
    }

    /** Ограничивает собственный output отдельным Temp каталогом, отвергает links и aliases предков. */
    public static Path requireOutput(Path output) throws Exception {
        Path absolute=output.toAbsolutePath().normalize();
        Path temporary=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if(!absolute.startsWith(temporary) || !absolute.getFileName().toString().equals("actual-output") ||
            !absolute.getParent().getFileName().toString().equals("restored-window"))
            throw new IllegalArgumentException("WEB_GOAL_OUTPUT_SCOPE");
        for(Path p=absolute;p!=null;p=p.getParent()) if(Files.exists(p,LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(p) || !p.toRealPath().equals(p))) throw new IllegalArgumentException("WEB_GOAL_LINK");
        return absolute;
    }

    /** Единственная разрешённая команда наблюдения: нельзя заполнять форму для получения ожидаемых полей. */
    public static void requireStep(Map<String,Object> step, int delivered) {
        if(delivered!=0 || !step.keySet().equals(Set.of("seq","type","n","command")) || Json.requireLong(step,"seq")<1 ||
            !"test.step".equals(Json.requireString(step,"type")) || Json.requireLong(step,"n")!=1 ||
            !"shot restored".equals(Json.requireString(step,"command")))
            throw new IllegalArgumentException("WEB_GOAL_UNEXPECTED_STEP");
    }

    /** Запускает только отдельный Edge/CDP, выполняет actual widget shot и ждёт своего stop marker. */
    public static void main(String[] args) throws Exception {
        if(args.length!=4) throw new IllegalArgumentException("WEB_GOAL_ARGS");
        Path output=requireOutput(Path.of(decode(args[0])));
        URI address=requireAddress(decode(args[1]));
        Path executable=Path.of(decode(args[2])).toRealPath();
        int seconds=Integer.parseInt(args[3]);
        if(seconds<10 || seconds>55 || !Files.isRegularFile(executable)) throw new IllegalArgumentException("WEB_GOAL_BOUNDS");
        Files.createDirectories(output);
        Path profile=output.resolve("browser-profile"),stop=output.resolve("web-bridge.stop");
        if(Files.exists(profile) || Files.exists(stop) || Files.exists(output.resolve("browser-owner.json")))
            throw new IllegalArgumentException("WEB_GOAL_OUTPUT_EXISTS");
        // План ownership записан ДО Edge, включая его возможную viewport calibration.
        write(output.resolve("browser-owner.json"),Map.of("profile",profile.toString(),"executable",executable.toString()));
        BrowserSession browser=null;CdpClient cdp=null;Exception failure=null;
        Instant launched=Instant.now();long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        int delivered=0;
        try {
            browser=EdgeLauncher.startWithViewport(executable,profile,1200,800,address.toString(),Duration.ofSeconds(10));
            cdp=CdpClient.connectToFirstPage(browser.port(),Duration.ofSeconds(10));
            cdp.waitFor("document.readyState === 'complete' && !!window.cpParityTestApi",Duration.ofSeconds(10));
            // Transport удаляет token из URL после bootstrap: проверяется actual origin/path, не старый query.
            if(!Boolean.TRUE.equals(cdp.evaluate("location.origin === "+UiJson.write("http://127.0.0.1:"+address.getPort())+
                " && location.pathname === '/'",Duration.ofSeconds(5))))
                throw new IllegalStateException("WEB_GOAL_WRONG_PAGE");
            CdpTestApi api=new CdpTestApi(cdp);
            TestApiBridge bridge=new TestApiBridge(new UiTestDriver(ClientKind.WEB,api),TestApiBridge.http(address));
            write(output.resolve("bridge-attached.json"),Map.of("status","ACTUAL_CDP_ATTACHED","profile",profile.toString(),
                "executable",executable.toString(),"browserLauncherPid",browser.process().pid(),"port",browser.port(),
                "bridgePid",ProcessHandle.current().pid(),"bridgeStartedAt",ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                "attachedAt",Instant.now().toString(),"viewport",browser.viewportSize(),"nativePass",false));
            while(!Files.exists(stop)) {
                if(System.nanoTime()>=deadline) throw new IllegalStateException("WEB_GOAL_BRIDGE_TIMEOUT");
                Map<String,Object> step=api.takeStep();
                if(step!=null) {
                    requireStep(step,delivered);
                    // Единственный producer POST - существующий TestApiBridge с actual CdpTestApi.
                    bridge.accept(step);delivered++;
                    Path raw=output.resolve("phase-restored/restored.raw.json"),png=output.resolve("phase-restored/restored.png");
                    if(!Files.isRegularFile(raw) || !Files.isRegularFile(png)) throw new IllegalStateException("WEB_GOAL_SHOT_MISSING");
                    write(output.resolve("bridge-shot.json"),Map.of("status","DOM_SHOT_DELIVERED_NOT_NATIVE_PASS",
                        "rawSha256",sha(raw),"pngSha256",sha(png),"deliveredSteps",delivered,"observedAt",Instant.now().toString(),"nativePass",false));
                } else Thread.sleep(20);
            }
            if(Files.size(stop)!=0) throw new IllegalArgumentException("WEB_GOAL_STOP_CONTENT");
        } catch(Exception error) {failure=error;throw error;}
        finally {
            Exception cleanup=null;
            try {if(cdp!=null) cdp.close();} catch(Exception e) {cleanup=e;}
            try {if(browser!=null) browser.close();} catch(Exception e) {if(cleanup==null) cleanup=e;else cleanup.addSuppressed(e);}
            write(output.resolve("bridge-cleanup.json"),Map.of("startedAt",launched.toString(),"finishedAt",Instant.now().toString(),
                "deliveredSteps",delivered,"cleanupStatus",cleanup==null?"OWNED_BROWSER_CLOSED":"FAIL",
                "error",cleanup==null?"":cleanup.toString(),"profileRemoved",!Files.exists(profile),"nativePass",false,"ordinaryExitProven",false));
            if(cleanup!=null) {if(failure!=null) failure.addSuppressed(cleanup);else throw cleanup;}
        }
    }

    /** UTF-8 Base64 исключает проблемы Unicode путей в аргументах Windows source bridge. */
    private static String decode(String value) {return new String(Base64.getDecoder().decode(value),java.nio.charset.StandardCharsets.UTF_8);}
    /** SHA считается по настоящим complete artefact bytes после actual POST acknowledgment. */
    private static String sha(Path path) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    /** Атомарная публикация complete receipts без создания модельного dump. */
    private static void write(Path path,Object value) throws Exception {
        Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(temporary,UiJson.write(value),StandardOpenOption.CREATE_NEW);
        Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE);
    }
}
