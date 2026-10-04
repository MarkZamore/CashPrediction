package ru.cashprediction.core.update.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Структура переносимой Windows-сборки по jpackage и PortableBootstrap.
 * Файлы бинарных компонентов содержат тестовые байты, не исполняемые PE/JVM.
 * Fixture проверяет состав и хеши, но не доказывает запуск настоящей сборки.
 */
final class NetPortableFixture {
    // Независимый от production-константы перечень требуемого bootstrap inventory.
    static final List<String> REQUIRED = List.of("CashPrediction.exe", "CashPrediction-Swing.exe",
            "CashPrediction-Web.exe", "app/CashPrediction.cfg", "app/CashPrediction-Swing.cfg",
            "app/CashPrediction-Web.cfg", "app/.jpackage.xml", "runtime/bin/jli.dll",
            "runtime/bin/server/jvm.dll", "runtime/lib/modules");

    private NetPortableFixture() { }

    static void create(Path root) throws IOException {
        for (String path : REQUIRED) {
            Files.createDirectories(root.resolve(path).getParent());
            Files.writeString(root.resolve(path), "fixture-" + path);
        }
        // Модули и точки входа взяты из действительного dist jpackage.
        Files.writeString(root.resolve("app/CashPrediction.cfg"),
                "[Application]\napp.mainmodule=ru.cashprediction.fx/ru.cashprediction.fx.FxMain\n");
        Files.writeString(root.resolve("app/CashPrediction-Swing.cfg"),
                "[Application]\napp.mainmodule=ru.cashprediction.swing/ru.cashprediction.swing.SwingMain\n");
        Files.writeString(root.resolve("app/CashPrediction-Web.cfg"),
                "[Application]\napp.mainmodule=ru.cashprediction.web/ru.cashprediction.web.WebMain\n");
        Files.writeString(root.resolve("app/.jpackage.xml"), "<jpackage-state/>\n");
    }
}
