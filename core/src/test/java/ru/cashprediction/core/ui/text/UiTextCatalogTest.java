package ru.cashprediction.core.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.text.CoreModuleDir;
import ru.cashprediction.core.text.TextKeyUsage;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.token.DesignTokens;

/**
 * Проверка каталога текстов (спецификация v2, §8; архитектура §3.1; решение L13).
 *
 * <p><b>Всегда:</b> файлы загружаются; нет повторов ключей в файле и между файлами; ключи правильной формы;
 * подстановки идут подряд с {@code {0}} и совпадают у вариантов одного текста; у {@code plural.*} три формы; только
 * разрешённые символы (кириллица, ASCII, типографские знаки, {@code DesignTokens.GLYPHS}); нет ISO-дат и латинского
 * «OK»; слова формата файлов ({@code FormatWords}) не смешиваются с ключами каталога.</p>
 *
 * <p><b>Ссылки на ключи</b> собирает {@link TextKeyUsage} по наборам исходников {@link #SOURCES}, у каждого свой
 * владелец (ядро, рендереры клиентов). Проверяется: каждый ключ, переданный литералом в {@code UiText.get/has/template}
 * или {@code Texts.get/has}, существует; литерал формы ключа из пространства имён областей слоёв ядра (например
 * {@code window.title.x} в ветке {@code switch}) тоже существует; число аргументов {@code get} совпадает с числом
 * подстановок текста, где оно видно статически.</p>
 *
 * <p><b>Области слоёв ядра</b> ({@link #CORE_LAYER_AREAS}, этап S0.5): неиспользуемый ключ в них — ошибка уже сейчас
 * ({@link #noUnusedKeysInCoreLayerAreas()}), потому что их тексты перенесены из работающего кода.</p>
 *
 * <p><b>Неиспользуемые ключи</b> остальных областей ({@link #noUnusedKeys()}): ключ считается используемым, если он
 * встречается строковым литералом в исходниках {@link #SOURCES} или строится из таблицы ({@link #dynamicKeys()}). На
 * этапах S0–S1 проверка только сообщает список (потребители текстов появляются в S1–S2); с этапа S2 она включается
 * свойством {@value #REQUIRE_ALL_USED}{@code =true} в core/pom.xml. Этапы S1–S3 добавляют сюда свои таблицы ключей
 * ({@code MenuModels}, {@code HotkeyTable}) и наборы исходников клиентов (для web — JS-файлы со своим шаблоном вызова),
 * а проверки по отдельному клиенту строят на {@link TextKeyUsage#byOwner(List)}.</p>
 */
class UiTextCatalogTest {

    /** Свойство, включающее обязательную проверку неиспользуемых ключей. */
    static final String REQUIRE_ALL_USED = "cashprediction.catalog.requireAllUsed";

    /**
     * Области слоёв ядра без интерфейса, заполненные этапом S0.5 переносом готовых сообщений из кода ({@code model}
     * создана на этапе S0 и дополнена в S0.5). Для них проверка неиспользуемых ключей обязательна уже сейчас
     * ({@link #noUnusedKeysInCoreLayerAreas()}), а первые части их ключей — пространства имён, в которых литерал формы
     * ключа обязан существовать ({@link #everyReferencedKeyExists()}).
     */
    static final List<String> CORE_LAYER_AREAS = List.of("model", "dates", "markdown", "diagnostics", "document",
            "export", "forecast", "io", "json", "session");

    /** Владелец набора исходников ядра. */
    static final String CORE = "core";

    /**
     * Наборы исходников, где ищутся ссылки на ключи (пути от папки модуля core, {@link CoreModuleDir}). Корень ядра
     * обязателен ({@link #requiredSourceRootsExist()}); корни рендереров клиентов появляются на этапах S1–S3 и до того
     * пропускаются.
     */
    static final List<TextKeyUsage.SourceSet> SOURCES = List.of(
            TextKeyUsage.SourceSet.java(CORE, CoreModuleDir.resolve("src/main/java"), true),
            TextKeyUsage.SourceSet.java("ui-fx", CoreModuleDir.resolve("../ui-fx/src/main/java/ru/cashprediction/fx/ui"), false),
            TextKeyUsage.SourceSet.java("ui-swing",
                    CoreModuleDir.resolve("../ui-swing/src/main/java/ru/cashprediction/swing/ui"), false),
            TextKeyUsage.SourceSet.java("web", CoreModuleDir.resolve("../web/src/main/java/ru/cashprediction/web/ui"), false));

    /** Ключ: латиница, цифры, дефисы и подчёркивания, части через точку. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");
    /** ISO-дата ГГГГ-ММ-ДД запрещена в интерфейсе. */
    private static final Pattern ISO_DATE = Pattern.compile("(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9])");
    /** Латинское «OK»: кнопка по умолчанию пишется кириллицей «ОК». */
    private static final Pattern LATIN_OK = Pattern.compile("(?<![A-Za-z])OK(?![A-Za-z])");

    /** Ссылки на ключи во всех наборах исходников: собираются один раз на запуск класса. */
    private static List<TextKeyUsage.Reference> references;

    @Test
    void requiredSourceRootsExist() {
        // Иначе проверки ссылок на ключи молча нашли бы ноль исходников (запуск из чужой рабочей папки).
        for (TextKeyUsage.SourceSet source : SOURCES) {
            assertTrue(!source.required() || source.root().toFile().isDirectory(), source.toString());
        }
    }

    @Test
    void allAreaFilesLoad() {
        assertEquals(List.of(), UiText.loadProblems());
    }

    @Test
    void noDuplicateKeysAcrossOrWithinFiles() {
        assertEquals(List.of(), UiText.duplicates());
    }

    @Test
    void keysAreWellFormed() {
        List<String> bad = UiText.keys().stream().filter(key -> !KEY.matcher(key).matches()).toList();
        assertEquals(List.of(), bad);
    }

    @Test
    void placeholdersAreContiguousFromZero() {
        List<String> bad = new ArrayList<>();
        for (String key : UiText.keys()) {
            Set<Integer> found = UiText.placeholders(UiText.template(key).orElseThrow());
            Set<Integer> expected = new TreeSet<>();
            for (int i = 0; i < found.size(); i++) {
                expected.add(i);
            }
            if (!found.equals(expected)) {
                bad.add(key + " " + found);
            }
        }
        assertEquals(List.of(), bad, "подстановки должны идти подряд с {0}");
    }

    @Test
    void variantsOfOneTextHaveSamePlaceholders() {
        // Варианты одного текста (main.title и main.title.dirty) получают одинаковые аргументы от одного вызывающего кода.
        List<String> bad = new ArrayList<>();
        for (String key : UiText.keys()) {
            for (String suffix : List.of(".dirty")) {
                String variant = key + suffix;
                if (UiText.has(variant) && !UiText.placeholders(UiText.template(key).orElseThrow())
                        .equals(UiText.placeholders(UiText.template(variant).orElseThrow()))) {
                    bad.add(key + " / " + variant);
                }
            }
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void pluralKeysHaveThreeForms() {
        List<String> bad = UiText.keys().stream()
                .filter(key -> key.startsWith("plural.") && UiText.template(key).orElseThrow().split("\\|", -1).length != 3)
                .toList();
        assertEquals(List.of(), bad);
    }

    @Test
    void onlyAllowedCharacters() {
        List<String> bad = new ArrayList<>();
        for (String key : UiText.keys()) {
            String text = UiText.template(key).orElseThrow();
            text.codePoints().filter(cp -> !DesignTokens.isAllowedInText(cp))
                    .forEach(cp -> bad.add(key + " U+" + Integer.toHexString(cp).toUpperCase(Locale.ROOT)));
        }
        assertEquals(List.of(), bad, "символ вне кириллицы, ASCII, типографских знаков и DesignTokens.GLYPHS");
    }

    @Test
    void noIsoDatesAndNoLatinOk() {
        List<String> bad = new ArrayList<>();
        for (String key : UiText.keys()) {
            String text = UiText.template(key).orElseThrow();
            if (ISO_DATE.matcher(text).find()) {
                bad.add(key + ": ISO-дата");
            }
            if (LATIN_OK.matcher(text).find()) {
                bad.add(key + ": латинское OK");
            }
        }
        assertEquals(List.of(), bad);
    }

    @Test
    void everyReferencedKeyExists() {
        List<TextKeyUsage.Reference> all = references();
        assertEquals(List.of(), TextKeyUsage.missing(all, UiText::has), "ключи, которых нет в каталоге");
        long lookups = all.stream().filter(reference -> reference.via() == TextKeyUsage.Via.LOOKUP).count();
        assertTrue(lookups > 300, "сканер находит вызовы UiText.get/Texts.get: " + lookups);
    }

    @Test
    void argumentCountsMatchPlaceholders() {
        // Лишний аргумент молча теряется, недостающий у пользователя оставляет «{1}» в тексте: оба — ошибки перевода.
        List<TextKeyUsage.Reference> all = references();
        assertEquals(List.of(), TextKeyUsage.argumentMismatches(all, UiText::template),
                "число аргументов UiText.get/Texts.get не совпадает с подстановками текста");
        int checked = TextKeyUsage.checkedCalls(all).size();
        assertTrue(checked > 300, "число аргументов видно у большинства вызовов: " + checked);
    }

    @Test
    void referencesAreAttributedToTheirSourceSet() {
        // Основа проверок по клиентам: каждая ссылка принадлежит набору исходников, в корне которого лежит её файл.
        Map<String, Set<String>> byOwner = TextKeyUsage.byOwner(references());
        assertTrue(byOwner.containsKey(CORE), byOwner.keySet().toString());
        for (TextKeyUsage.Reference reference : references()) {
            TextKeyUsage.SourceSet source = SOURCES.stream()
                    .filter(set -> set.owner().equals(reference.owner())).findFirst().orElseThrow();
            assertTrue(reference.file().startsWith(source.root()), reference.location());
        }
        Set<String> coreLayerKeys = UiText.keys().stream().filter(UiTextCatalogTest::isCoreLayerKey)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> notUsedByCore = new TreeSet<>(coreLayerKeys);
        notUsedByCore.removeAll(byOwner.get(CORE));
        assertEquals(Set.of(), notUsedByCore, "тексты слоёв ядра использует само ядро");
    }

    @Test
    void noUnusedKeys() {
        Set<String> used = usedKeys();
        List<String> unused = UiText.keys().stream().filter(key -> !used.contains(key)).toList();
        Assumptions.assumeTrue(Boolean.getBoolean(REQUIRE_ALL_USED),
                () -> "S0-S1: неиспользуемые ключи только сообщаются (" + unused.size() + "): " + unused);
        assertEquals(List.of(), unused, "ключи каталога, на которые нет ссылок");
    }

    @Test
    void noUnusedKeysInCoreLayerAreas() {
        // Области S0.5 заполнены переносом готового кода ядра: у каждого их ключа потребитель уже есть, поэтому
        // неиспользуемый ключ здесь — ошибка сразу, а не только с этапа S2.
        Set<String> used = usedKeys();
        List<String> unused = UiText.keys().stream()
                .filter(UiTextCatalogTest::isCoreLayerKey)
                .filter(key -> !used.contains(key))
                .toList();
        assertEquals(List.of(), unused, "ключи областей слоёв ядра, на которые нет ссылок");
    }

    @Test
    void coreLayerAreasAreLoaded() {
        assertTrue(UiText.AREAS.containsAll(CORE_LAYER_AREAS), "области S0.5 зарегистрированы в Texts.AREAS");
        for (String area : CORE_LAYER_AREAS) {
            assertTrue(UiText.keys().stream().anyMatch(key -> UiText.area(key).orElse("").equals(area)),
                    "область " + area + " не пуста");
        }
    }

    @Test
    void formatWordsAreNotCatalogueTexts() throws IOException {
        // Слова формата файлов — грамматика данных в нелокализуемом ресурсе: ни области «format», ни одинаковых имён,
        // ни файла словаря формата среди файлов каталога (иначе перевод интерфейса изменил бы формат файлов).
        assertFalse(UiText.AREAS.contains("format"), UiText.AREAS.toString());
        Set<String> overlap = new TreeSet<>(FormatWords.names());
        overlap.retainAll(UiText.keys());
        assertEquals(Set.of(), overlap, "имя есть и в format.properties, и в каталоге текстов");
        Path catalogueDir = CoreModuleDir.resolve("src/main/resources" + UiText.RESOURCE_DIR);
        try (var files = Files.list(catalogueDir)) {
            List<String> formatFiles = files.map(file -> file.getFileName().toString())
                    .filter(name -> name.startsWith("format")).toList();
            assertEquals(List.of(), formatFiles, "словарь формата не лежит среди файлов каталога");
        }
    }

    @Test
    void checksThemselvesCatchViolations(@TempDir Path dir) throws IOException {
        // Самопроверка шаблонов и сбора ссылок, чтобы проверки выше не были пустой формальностью.
        assertTrue(ISO_DATE.matcher("до 2027-08-31").find());
        assertTrue(LATIN_OK.matcher("[OK]").find());
        assertFalse(LATIN_OK.matcher("ОК").find(), "кириллическое ОК допустимо");
        assertFalse(DesignTokens.isAllowedInText(0x1F4B0), "эмодзи запрещены");
        assertFalse(DesignTokens.isAllowedInText('é'));
        assertTrue(DesignTokens.isAllowedInText('⟲') && DesignTokens.isAllowedInText('«') && DesignTokens.isAllowedInText('ё'));
        assertTrue(!KEY.matcher("menu..new").matches() && KEY.matcher("view.period.M3.tip").matches());
        assertTrue(TextKeyUsage.JAVA_LOOKUP_CALL.matcher("x = UiText.get(").find());
        assertTrue(TextKeyUsage.JAVA_LOOKUP_CALL.matcher("throw new X(Texts.get( ").find());
        assertFalse(TextKeyUsage.JAVA_LOOKUP_CALL.matcher("rule(\"r1\", ").find());
        assertTrue(usedKeys().contains("money.error.grouping"), "ключ модели используется через Texts.get");
        assertTrue(usedKeys().contains("sample.rule.salary"), "ключ, переданный литералом в метод, считается используемым");
        assertTrue(usedKeys().contains("window.title.adjustmentEditor"), "ключ из ветки switch считается используемым");

        Files.writeString(dir.resolve("Sample.java"), """
                class Sample {
                    String ok = Texts.get("money.error.invalid", text);
                    String fewer = Texts.get("money.error.invalid");
                    String more = UiText.get("money.error.empty", text);
                    String typo = switch (kind) { case A -> "window.title.noSuchWindow"; default -> ""; };
                    String word = FormatWords.get("session.md.owner");
                    String property = System.getProperty("cashprediction.home");
                }
                """, StandardCharsets.UTF_8);
        List<TextKeyUsage.Reference> sample = TextKeyUsage.collect(
                List.of(TextKeyUsage.SourceSet.java("sample", dir, true)), UiText.keys(), coreLayerNamespaces(),
                FormatWords::has);
        assertEquals(List.of("money.error.invalid", "money.error.invalid", "money.error.empty", "window.title.noSuchWindow"),
                sample.stream().map(TextKeyUsage.Reference::key).toList());
        assertEquals(1, TextKeyUsage.missing(sample, UiText::has).size(), "опечатка в ключе из switch найдена");
        assertEquals(2, TextKeyUsage.argumentMismatches(sample, UiText::template).size(),
                "и недостающий, и лишний аргумент найдены: " + TextKeyUsage.argumentMismatches(sample, UiText::template));
        assertEquals(Optional.of("get"), sample.stream().map(TextKeyUsage.Reference::method).findFirst());
    }

    /** @return ссылки на ключи во всех наборах исходников (один проход сканера на запуск класса) */
    private static synchronized List<TextKeyUsage.Reference> references() {
        if (references == null) {
            references = TextKeyUsage.collect(SOURCES, UiText.keys(), coreLayerNamespaces(), FormatWords::has);
        }
        return references;
    }

    /** @return первые части ключей областей слоёв ядра, например {@code money}, {@code window}, {@code csv} */
    private static Set<String> coreLayerNamespaces() {
        Set<String> namespaces = new TreeSet<>();
        for (String key : UiText.keys()) {
            if (isCoreLayerKey(key) && key.indexOf('.') > 0) {
                namespaces.add(key.substring(0, key.indexOf('.')));
            }
        }
        return namespaces;
    }

    /** @return принадлежит ли ключ области слоя ядра */
    private static boolean isCoreLayerKey(String key) {
        return UiText.area(key).map(CORE_LAYER_AREAS::contains).orElse(false);
    }

    /** @return ключи, на которые есть литерал в исходниках или которые строятся из таблиц */
    private static Set<String> usedKeys() {
        Set<String> used = new LinkedHashSet<>(dynamicKeys());
        used.addAll(TextKeyUsage.usedKeys(references()));
        return used;
    }

    /**
     * Ключи, которые код строит во время работы, а не пишет литералом. Этапы S1–S2 дополняют список своими таблицами.
     *
     * @return ключи
     */
    private static Set<String> dynamicKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (CommandId command : CommandId.values()) {
            keys.add(command.menuKey());
            keys.add(command.tipKey());
        }
        return keys;
    }
}
