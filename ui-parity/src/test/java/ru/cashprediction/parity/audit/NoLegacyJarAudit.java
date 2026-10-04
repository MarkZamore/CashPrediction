package ru.cashprediction.parity.audit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Проверяет байты собранных jar: удаление исходника не гарантирует удаления старого class-файла. */
public final class NoLegacyJarAudit {
    private NoLegacyJarAudit() { }
    // Помимо §7 включены согласованные остатки старого транспорта. ApiResponse и ApiException остаются общими.
    // До включения gate новый UiApi должен перестать ссылаться на константы старого ApiHandler.
    private static final Set<String> WEB_CLASSES = Set.of("PlanEditApi", "FileApi", "PlanFileCommands", "PlanForms",
            "ViewApi", "SessionApi", "StateJson", "WebWindow", "ServerState", "ServerUiExecutor", "FolderBrowserApi",
            "ApiHandler", "Router", "ApiRequest", "ConflictException");
    private static final Set<String> WEB_RESOURCES = Set.of("app.js", "app-menu.js", "commands.js", "dialogs.js",
            "plan-dialogs.js", "editors.js", "forms.js", "table.js", "chart.js", "summary.js", "popup.js", "session.js",
            "tools.js", "windows.js", "util.js", "store.js", "api.js", "menu.js", "style.css");

    /** Проверяет один настоящий jar указанного модуля; отсутствие обязательных записей является ошибкой. */
    public static void verify(String module, Path path) throws Exception {
        if (!Set.of("core", "ui-fx", "ui-swing", "web").contains(module)) throw new IllegalArgumentException("Unknown module");
        var present = new HashSet<String>();
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String physical = entry.getName();
                String name = physical.replaceFirst("^META-INF/versions/[0-9]+/", "");
                if (physical.contains("\\") || physical.startsWith("/") || Arrays.asList(physical.split("/")).contains(".."))
                    throw new IllegalArgumentException("Unsafe jar entry " + physical);
                if (!present.add(physical)) throw new IllegalArgumentException("Duplicate jar entry " + physical);
                if (entry.getSize() == 0 && required(module).contains(physical)) throw new IllegalArgumentException("Empty required entry " + physical);
                rejectLegacy(module, name);
                if (module.equals("web") && name.equals("web/index.html")) {
                    String html;
                    try (var input = jar.getInputStream(entry)) { html = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
                    for (String legacy : WEB_RESOURCES) {
                        String pattern = "(?i)(?:src|href)\\s*=\\s*[\"'](?:/|\\./)?" + Pattern.quote(legacy) + "(?:[?#][^\"']*)?[\"']";
                        if (Pattern.compile(pattern).matcher(html).find()) throw new IllegalArgumentException("Legacy index reference " + legacy);
                    }
                }
            }
        }
        if (!present.containsAll(required(module))) {
            var missing = new TreeSet<>(required(module)); missing.removeAll(present);
            throw new IllegalArgumentException("Missing required module entries " + module + " " + missing);
        }
    }

    /** Точный allowlist настольных классов и список удаления Web из архитектуры §7. */
    private static void rejectLegacy(String module, String name) {
        if (module.equals("ui-fx") || module.equals("ui-swing")) {
            String client = module.equals("ui-fx") ? "fx" : "swing";
            String root = "ru/cashprediction/" + client + "/";
            String main = root + (client.equals("fx") ? "FxMain" : "SwingMain");
            if (name.endsWith(".class") && !name.equals("module-info.class") && !name.startsWith(root + "ui/")
                    && !name.equals(main + ".class") && !name.startsWith(main + "$"))
                throw new IllegalArgumentException("Legacy desktop class " + name);
            if (module.equals("ui-fx") && (name.equals("styles.css") || name.endsWith("/styles.css")) && !name.startsWith(root + "ui/"))
                throw new IllegalArgumentException("Legacy FX stylesheet " + name);
        } else if (module.equals("web")) {
            for (String old : WEB_CLASSES) {
                String prefix = "ru/cashprediction/web/" + old;
                if (name.equals(prefix + ".class") || name.startsWith(prefix + "$"))
                    throw new IllegalArgumentException("Legacy Web class " + name);
            }
            if (name.startsWith("web/") && WEB_RESOURCES.contains(name.substring(4)))
                throw new IllegalArgumentException("Legacy Web resource " + name);
        } else if (name.equals("ru/cashprediction/core/app/LaunchOptions$UiMode.class")
                || name.startsWith("ru/cashprediction/core/app/LaunchOptions$UiMode$")) {
            throw new IllegalArgumentException("Legacy launch mode " + name);
        }
    }

    /** Минимальный реальный состав модуля не позволяет засчитать пустой jar за очищенную сборку. */
    static Set<String> required(String module) {
        return switch (module) {
            case "ui-fx" -> Set.of("module-info.class", "ru/cashprediction/fx/FxMain.class", "ru/cashprediction/fx/ui/FxUiPort.class");
            case "ui-swing" -> Set.of("module-info.class", "ru/cashprediction/swing/SwingMain.class", "ru/cashprediction/swing/ui/SwingUiPort.class");
            case "web" -> Set.of("module-info.class", "ru/cashprediction/web/WebMain.class", "ru/cashprediction/web/WebServer.class",
                    "ru/cashprediction/web/ui/UiApi.class", "web/index.html", "web/app/main.js");
            case "core" -> Set.of("module-info.class", "ru/cashprediction/core/app/LaunchOptions.class", "ru/cashprediction/core/model/Plan.class");
            default -> throw new IllegalArgumentException("Unknown module");
        };
    }
}
