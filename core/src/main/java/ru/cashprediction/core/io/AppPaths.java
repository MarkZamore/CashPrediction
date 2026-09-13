package ru.cashprediction.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

/**
 * Определяет домашнюю папку приложения, рядом с которой создаётся {@code CashMemory}.
 *
 * <p>Порядок поиска (первый сработавший выигрывает):</p>
 * <ol>
 *   <li>системное свойство {@code cashprediction.home} задаётся в dev-режиме Maven-плагинами,
 *       чтобы CashMemory появлялась в корне проекта, а не в случайном рабочем каталоге;</li>
 *   <li>{@code jpackage.app-path}: лаунчер jpackage подставляет полный путь к запущенному .exe,
 *       его родитель и есть портативная папка дистрибутива;</li>
 *   <li>папка, в которой лежит jar с этим классом (запуск {@code java -jar} из произвольного места);</li>
 *   <li>{@code user.dir} как крайний случай.</li>
 * </ol>
 *
 * <p>На рабочий каталог процесса намеренно не полагаемся: ярлык Windows может задать любой.</p>
 *
 * <p>Класс потокобезопасен: состояния нет, все методы чистые.</p>
 */
public final class AppPaths {

    /** Имя папки с данными приложения; единственное, что программа создаёт на диске. */
    public static final String CASH_MEMORY_DIR = "CashMemory";

    private AppPaths() {
    }

    /**
     * Возвращает папку приложения (см. описание класса).
     *
     * @return абсолютный путь к папке, рядом с которой живёт CashMemory
     */
    public static Path appHome() {
        String explicit = System.getProperty("cashprediction.home");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null && !launcher.isBlank()) {
            Path parent = Path.of(launcher).toAbsolutePath().getParent();
            if (parent != null) {
                return parent.normalize();
            }
        }
        try {
            CodeSource source = AppPaths.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path location = Path.of(source.getLocation().toURI());
                // Для jar берём его папку; каталог classes (запуск из IDE) не годится.
                if (Files.isRegularFile(location) && location.getParent() != null) {
                    return location.getParent().toAbsolutePath().normalize();
                }
            }
        } catch (Exception ignored) {
            // Любая ошибка определения местоположения: просто переходим к последнему варианту.
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /**
     * Возвращает путь к папке CashMemory, не создавая её.
     *
     * @return {@code appHome()/CashMemory}
     */
    public static Path cashMemory() {
        return appHome().resolve(CASH_MEMORY_DIR);
    }

    /**
     * Создаёт папку CashMemory, если её ещё нет, и возвращает путь к ней.
     *
     * @return путь к существующей папке CashMemory
     * @throws IOException если папку создать не удалось (например, носитель только для чтения)
     */
    public static Path ensureCashMemory() throws IOException {
        Path dir = cashMemory();
        Files.createDirectories(dir);
        return dir;
    }
}
