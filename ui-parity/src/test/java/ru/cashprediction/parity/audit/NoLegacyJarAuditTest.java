package ru.cashprediction.parity.audit;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Независимые синтетические проверки фильтра; не заменяют аудит настоящих reactor jar. */
public final class NoLegacyJarAuditTest {
    @TempDir Path temporary;

    /** Все допустимые новые раскладки принимаются; содержимое fixture не выдаётся за исполняемый bytecode. */
    @Test void cleanLayoutsAreAccepted() throws Exception {
        for (String module : List.of("core", "ui-fx", "ui-swing", "web"))
            NoLegacyJarAudit.verify(module, fixture(module, Map.of()));
    }

    /** Старый корневой класс FX отклоняется, включая вложенный класс. */
    @Test void staleFxRootClassIsRejected() throws Exception {
        reject("ui-fx", "ru/cashprediction/fx/MainWindow$Listener.class");
    }

    /** Старый пакет Swing отклоняется даже при сохранённых новых entrypoint и UI. */
    @Test void staleSwingPackageIsRejected() throws Exception {
        reject("ui-swing", "ru/cashprediction/swing/session/SwingWindowFactory.class");
    }

    /** Старый обработчик Web и вложенный класс также запрещены. */
    @Test void staleWebJavaClassIsRejected() throws Exception {
        reject("web", "ru/cashprediction/web/PlanForms$Editor.class");
    }

    /** Старый HTTP-handler не сохраняется ради констант, которые должен перенести новый транспорт. */
    @Test void staleApiHandlerIsRejected() throws Exception {
        reject("web", "ru/cashprediction/web/ApiHandler.class");
    }

    /** Старый router и его вложенные типы не принадлежат новому UiApi. */
    @Test void staleRouterIsRejected() throws Exception {
        reject("web", "ru/cashprediction/web/Router$Match.class");
    }

    /** Старый конверт запроса не должен выжить после удаления legacy-транспорта. */
    @Test void staleApiRequestIsRejected() throws Exception {
        reject("web", "ru/cashprediction/web/ApiRequest.class");
    }

    /** Исключение конфликтов старого API отклоняется отдельно от общей ApiException. */
    @Test void staleConflictExceptionIsRejected() throws Exception {
        reject("web", "ru/cashprediction/web/ConflictException.class");
        NoLegacyJarAudit.verify("web", fixture("web", Map.of("ru/cashprediction/web/ApiException.class", "shared",
                "ru/cashprediction/web/ApiResponse.class", "shared response")));
    }

    /** Пустая обязательная запись не заменяет скомпилированный descriptor или entrypoint. */
    @Test void emptyRequiredEntryIsRejected() throws Exception {
        for (String module : List.of("core", "ui-fx", "ui-swing", "web")) {
            Path jar = fixture(module, Map.of("module-info.class", ""));
            var failure = assertThrows(IllegalArgumentException.class, () -> NoLegacyJarAudit.verify(module, jar));
            assertEquals("Empty required entry module-info.class", failure.getMessage());
        }
        Path jar = fixture("ui-fx", Map.of("ru/cashprediction/fx/FxMain.class", ""));
        assertThrows(IllegalArgumentException.class, () -> NoLegacyJarAudit.verify("ui-fx", jar));
    }

    /** Старый stylesheet запрещён на корневом и старом пакетном пути, новый адаптированный путь допустим. */
    @Test void staleStylesheetsAreRejected() throws Exception {
        reject("ui-fx", "styles.css");
        reject("ui-fx", "ru/cashprediction/fx/styles.css");
        reject("web", "web/style.css");
        NoLegacyJarAudit.verify("ui-fx", fixture("ui-fx", Map.of("ru/cashprediction/fx/ui/styles.css", "new stylesheet")));
    }

    /** Старый JS запрещён только на старом пути; новое web/app не маскируется тем же basename. */
    @Test void staleWebResourceIsRejected() throws Exception {
        reject("web", "web/app.js");
        NoLegacyJarAudit.verify("web", fixture("web", Map.of("web/app/table.js", "new module")));
    }

    /** Старый core enum не должен выжить в jar после удаления исходного режима запуска. */
    @Test void staleCoreLaunchModeIsRejected() throws Exception {
        reject("core", "ru/cashprediction/core/app/LaunchOptions$UiMode.class");
    }

    /** Multi-release записи не обходят проверку legacy по логическому имени класса. */
    @Test void multiReleaseLegacyClassIsRejected() throws Exception {
        reject("ui-fx", "META-INF/versions/25/ru/cashprediction/fx/action/FxActions.class");
    }

    /** Пустой jar и потерянный entrypoint отклоняются, а не считаются очищенной сборкой. */
    @Test void emptyAndMissingEntrypointJarsAreRejected() throws Exception {
        Path empty = temporary.resolve("empty.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(empty))) { }
        assertThrows(IllegalArgumentException.class, () -> NoLegacyJarAudit.verify("web", empty));
        Path partial = fixture("ui-fx", Map.of());
        Path missing = temporary.resolve("missing.jar");
        try (var source = new JarFile(partial.toFile()); var jar = new JarOutputStream(Files.newOutputStream(missing))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().endsWith("FxMain.class")) continue;
                jar.putNextEntry(new JarEntry(entry.getName()));
                try (var input = source.getInputStream(entry)) { input.transferTo(jar); }
                jar.closeEntry();
            }
        }
        assertThrows(IllegalArgumentException.class, () -> NoLegacyJarAudit.verify("ui-fx", missing));
    }

    /** Старый index не разрешается замаскировать сохранённым новым путём main.js. */
    @Test void oldIndexReferencingLegacyScriptIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> NoLegacyJarAudit.verify("web",
                fixture("web", Map.of("web/index.html", "<script src='/app.js'></script>"))));
    }

    /** Проверяет отказ на одном добавленном остаточном артефакте. */
    private void reject(String module, String name) throws Exception {
        Path jar = fixture(module, Map.of(name, "old bytes"));
        assertThrows(IllegalArgumentException.class, () -> NoLegacyJarAudit.verify(module, jar));
    }

    /** Создаёт отдельный JDK-only jar в TempDir с необходимыми именами и контролируемой добавкой. */
    private Path fixture(String module, Map<String, String> extra) throws Exception {
        var entries = new TreeMap<String, String>();
        for (String name : NoLegacyJarAudit.required(module)) entries.put(name, "fixture bytes");
        entries.put("web/index.html", "<script type='module' src='/app/main.js'></script>");
        if (!module.equals("web")) entries.remove("web/index.html");
        entries.putAll(extra);
        Path path = temporary.resolve(module + "-" + UUID.randomUUID() + ".jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey())); jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
            }
        }
        return path;
    }
}
