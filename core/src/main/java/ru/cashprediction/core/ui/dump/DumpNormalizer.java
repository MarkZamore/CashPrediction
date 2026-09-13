package ru.cashprediction.core.ui.dump;

import java.nio.file.Path;

/**
 * Нормализация дампа перед сравнением (архитектура §6.1): время {@code HH:mm:ss} → {@code <time>}, путь CashMemory →
 * {@code <CashMemory>}, узел реестра → {@code <node>}, ширины и координаты округляются до 2 px.
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class DumpNormalizer {

    private DumpNormalizer() {
    }

    /**
     * Нормализует дамп.
     *
     * @param dump         дамп клиента или модели
     * @param cashMemory   папка CashMemory запуска
     * @param registryNode узел реестра запуска (например {@code ru/cashprediction/selftest/<uuid>/fx}) или пустая строка
     * @return нормализованный дамп
     */
    public static UiDump normalize(UiDump dump, Path cashMemory, String registryNode) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — DumpNormalizer.normalize");
    }

    /**
     * Нормализует один текст по тем же правилам.
     *
     * @param text         текст
     * @param cashMemory   папка CashMemory
     * @param registryNode узел реестра или пустая строка
     * @return нормализованный текст
     */
    public static String normalizeText(String text, Path cashMemory, String registryNode) {
        throw new UnsupportedOperationException("S2: core-protocol-dump — DumpNormalizer.normalizeText");
    }
}
