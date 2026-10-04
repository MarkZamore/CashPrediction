package ru.cashprediction.core.ui.token;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Общие физические ресурсы значков трёх клиентов без зависимости от графических библиотек.
 * Символы остаются семантическими ключами для снимков и доступности; клиенты рисуют байты PNG.
 */
public final class UiIcons {
    private static final String ROOT = "/ru/cashprediction/core/ui/icons/";
    private static final String WEB_ROOT = "/app/icons/";
    private static final Set<ColorToken> VARIANT_COLORS = Set.of(ColorToken.ACCENT, ColorToken.WHATIF,
            ColorToken.EXPENSE, ColorToken.INCOME, ColorToken.TEXT_PRIMARY, ColorToken.TEXT_MUTED,
            ColorToken.WARN, ColorToken.TOOLTIP_TEXT, ColorToken.TEXT_PAST);
    private static final Map<String, String> MANIFEST = createManifest();
    private static final Set<String> FILENAMES = createFilenames();

    private UiIcons() {
    }

    /**
     * Возвращает новое содержимое PNG по символу или идентификатору значка.
     * @param key символ либо ASCII-идентификатор
     * @return независимый массив байтов или пустое значение для неизвестного ключа
     */
    public static Optional<byte[]> png(String key) {
        String path = MANIFEST.get(key);
        if (path != null) {
            return resource(path.substring(WEB_ROOT.length()));
        }
        return key != null && FILENAMES.contains(key + ".png")
                ? resource(key + ".png") : Optional.empty();
    }

    /**
     * Возвращает общий цветовой вариант; недоступный цвет сохраняет исходный значок.
     * @param key символ либо ASCII-идентификатор
     * @param color цвет контекста или null для исходного значка
     * @return независимые байты PNG либо пустое значение для неизвестного ключа
     */
    public static Optional<byte[]> png(String key, ColorToken color) {
        if (color != null && VARIANT_COLORS.contains(color)) {
            String path = MANIFEST.get(key);
            String basename = path == null ? key
                    : path.substring(WEB_ROOT.length(), path.length() - 4);
            String filename = basename + "-" + color.name().toLowerCase(Locale.ROOT) + ".png";
            try {
                Optional<byte[]> variant = resource(filename);
                if (variant.isPresent()) {
                    return variant;
                }
            } catch (IllegalStateException | UncheckedIOException ex) {
                // Отсутствие варианта не мешает загрузке исходного общего значка.
            }
        }
        return png(key);
    }

    /**
     * Возвращает независимый массив байтов исходного значка приложения.
     * @return общий PNG брендинга
     */
    public static byte[] applicationPng() {
        return resource("application.png").orElseThrow();
    }

    /**
     * Возвращает неизменяемое соответствие семантических ключей общим адресам ресурсов.
     * @return символы и идентификаторы с адресами /app/icons/
     */
    public static Map<String, String> manifest() {
        return MANIFEST;
    }

    /**
     * Загружает только известное имя файла из общего каталога; пути не принимаются.
     * @param filename точное имя файла без каталогов
     * @return независимые байты либо пустое значение для неизвестного имени
     */
    public static Optional<byte[]> resource(String filename) {
        if (!FILENAMES.contains(filename)) {
            return Optional.empty();
        }
        try (InputStream input = UiIcons.class.getResourceAsStream(ROOT + filename)) {
            if (input == null) {
                throw new IllegalStateException("Missing icon resource: " + filename);
            }
            return Optional.of(input.readAllBytes());
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read icon resource: " + filename, ex);
        }
    }

    private static Map<String, String> createManifest() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("↶", WEB_ROOT + "undo.png");
        result.put("↷", WEB_ROOT + "redo.png");
        result.put("▾", WEB_ROOT + "chevron-down.png");
        result.put("▸", WEB_ROOT + "chevron-right.png");
        result.put("✕", WEB_ROOT + "close.png");
        result.put("✓", WEB_ROOT + "check.png");
        result.put("✗", WEB_ROOT + "close.png");
        result.put("●", WEB_ROOT + "dot.png");
        result.put("◀", WEB_ROOT + "previous.png");
        result.put("▶", WEB_ROOT + "next.png");
        result.put("▦", WEB_ROOT + "calendar.png");
        result.put("↑", WEB_ROOT + "up.png");
        result.put("✎", WEB_ROOT + "edit.png");
        result.put("→", WEB_ROOT + "arrow-right.png");
        result.put("⇄", WEB_ROOT + "swap.png");
        result.put("≡", WEB_ROOT + "list.png");
        result.put("Δ", WEB_ROOT + "delta.png");
        result.put("₽", WEB_ROOT + "ruble.png");
        result.put("⚙", WEB_ROOT + "settings.png");
        result.put("↻", WEB_ROOT + "refresh.png");
        result.put("◎", WEB_ROOT + "target.png");
        result.put("⇩", WEB_ROOT + "download.png");
        result.put("⟲", WEB_ROOT + "restore.png");
        result.put("ℹ", WEB_ROOT + "info.png");
        result.put("⚠", WEB_ROOT + "warning.png");
        result.put("✖", WEB_ROOT + "error.png");
        result.put("?", WEB_ROOT + "question.png");
        result.put("‹", WEB_ROOT + "chevron-left.png");
        result.put("›", WEB_ROOT + "chevron-right.png");
        result.put("folder", WEB_ROOT + "folder.png");
        result.put("search", WEB_ROOT + "search.png");
        Set<String> basePaths = new LinkedHashSet<>(result.values());
        for (String path : basePaths) {
            String basename = path.substring(WEB_ROOT.length(), path.length() - 4);
            for (ColorToken color : ColorToken.values()) {
                if (VARIANT_COLORS.contains(color)) {
                    String alias = basename + "-" + color.name().toLowerCase(Locale.ROOT);
                    result.put(alias, WEB_ROOT + alias + ".png");
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> createFilenames() {
        Set<String> result = new LinkedHashSet<>();
        MANIFEST.values().forEach(path -> result.add(path.substring(WEB_ROOT.length())));
        result.add("application.png");
        result.add("application.ico");
        return Collections.unmodifiableSet(result);
    }
}
