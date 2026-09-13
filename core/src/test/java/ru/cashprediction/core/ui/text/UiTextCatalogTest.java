package ru.cashprediction.core.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.text.CoreModuleDir;
import ru.cashprediction.core.text.JavaSourceScanner;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.token.DesignTokens;

/**
 * Проверка каталога текстов (спецификация v2, §8; архитектура §3.1; решение L13).
 *
 * <p><b>Всегда:</b> файлы загружаются; нет повторов ключей в файле и между файлами; ключи правильной формы;
 * подстановки идут подряд с {@code {0}} и совпадают у вариантов одного текста; у {@code plural.*} три формы; только
 * разрешённые символы (кириллица, ASCII, типографские знаки, {@code DesignTokens.GLYPHS}); нет ISO-дат и латинского
 * «OK»; каждый ключ, переданный литералом в {@code UiText.get/has/template} или {@code Texts.get/has} в исходниках
 * ядра и новых рендереров клиентов ({@link #SOURCE_ROOTS}), существует.</p>
 *
 * <p><b>Неиспользуемые ключи</b> ({@link #noUnusedKeys()}): ключ считается используемым, если он встречается строковым
 * литералом в исходниках {@link #SOURCE_ROOTS} или строится из таблицы ({@link #dynamicKeys()}). На этапах S0–S1
 * проверка только сообщает список (потребители текстов появляются в S1–S2); с этапа S2 она включается свойством
 * {@value #REQUIRE_ALL_USED}{@code =true} в core/pom.xml. Этапы S1–S3 добавляют сюда свои таблицы ключей
 * ({@code MenuModels}, {@code HotkeyTable}) и корни исходников клиентов (для web — JS-файлы).</p>
 */
class UiTextCatalogTest {

    /** Свойство, включающее обязательную проверку неиспользуемых ключей. */
    static final String REQUIRE_ALL_USED = "cashprediction.catalog.requireAllUsed";

    /**
     * Корни исходников, где ищутся ссылки на ключи (от папки модуля core, {@link CoreModuleDir}). Корни клиентов
     * появляются на этапе S3; отсутствующий корень клиента пропускается, корень ядра обязан существовать
     * ({@link #coreSourceRootExists()}).
     */
    static final List<Path> SOURCE_ROOTS = List.of(
            CoreModuleDir.resolve("src/main/java"),
            CoreModuleDir.resolve("../ui-fx/src/main/java/ru/cashprediction/fx/ui"),
            CoreModuleDir.resolve("../ui-swing/src/main/java/ru/cashprediction/swing/ui"),
            CoreModuleDir.resolve("../web/src/main/java/ru/cashprediction/web/ui"));

    /** Ключ: латиница, цифры, дефисы и подчёркивания, части через точку. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");
    /** ISO-дата ГГГГ-ММ-ДД запрещена в интерфейсе. */
    private static final Pattern ISO_DATE = Pattern.compile("(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9])");
    /** Латинское «OK»: кнопка по умолчанию пишется кириллицей «ОК». */
    private static final Pattern LATIN_OK = Pattern.compile("(?<![A-Za-z])OK(?![A-Za-z])");
    /** Код прямо перед литералом-ключом: вызов поиска текста. */
    private static final Pattern LOOKUP_CALL = Pattern.compile("(?:UiText|Texts)\\s*\\.\\s*(?:get|has|template)\\s*\\(\\s*$");

    @Test
    void coreSourceRootExists() {
        // Иначе проверки ссылок на ключи молча нашли бы ноль исходников (запуск из чужой рабочей папки).
        assertTrue(SOURCE_ROOTS.getFirst().toFile().isDirectory(), SOURCE_ROOTS.getFirst().toString());
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
    void everyKeyLookedUpByLiteralExists() {
        List<String> missing = new ArrayList<>();
        int references = 0;
        for (Path root : SOURCE_ROOTS) {
            for (JavaSourceScanner.Literal literal : JavaSourceScanner.literals(root)) {
                if (LOOKUP_CALL.matcher(literal.codeBefore()).find()) {
                    references++;
                    if (!UiText.has(literal.raw())) {
                        missing.add(literal.file() + ":" + literal.line() + " " + literal.raw());
                    }
                }
            }
        }
        assertEquals(List.of(), missing, "ключи, которых нет в каталоге");
        assertTrue(references > 10, "сканер находит вызовы UiText.get/Texts.get: " + references);
    }

    @Test
    void noUnusedKeys() {
        Set<String> used = usedKeys();
        List<String> unused = UiText.keys().stream().filter(key -> !used.contains(key)).toList();
        Assumptions.assumeTrue(Boolean.getBoolean(REQUIRE_ALL_USED),
                () -> "S0–S1: неиспользуемые ключи только сообщаются (" + unused.size() + "): " + unused);
        assertEquals(List.of(), unused, "ключи каталога, на которые нет ссылок");
    }

    @Test
    void checksThemselvesCatchViolations() {
        // Самопроверка шаблонов, чтобы почти пустой каталог S0 не делал тесты выше пустой формальностью.
        assertTrue(ISO_DATE.matcher("до 2027-08-31").find());
        assertTrue(LATIN_OK.matcher("[OK]").find());
        assertFalse(LATIN_OK.matcher("ОК").find(), "кириллическое ОК допустимо");
        assertFalse(DesignTokens.isAllowedInText(0x1F4B0), "эмодзи запрещены");
        assertFalse(DesignTokens.isAllowedInText('é'));
        assertTrue(DesignTokens.isAllowedInText('⟲') && DesignTokens.isAllowedInText('«') && DesignTokens.isAllowedInText('ё'));
        assertTrue(!KEY.matcher("menu..new").matches() && KEY.matcher("view.period.M3.tip").matches());
        assertTrue(LOOKUP_CALL.matcher("x = UiText.get(").find());
        assertTrue(LOOKUP_CALL.matcher("throw new X(Texts.get( ").find());
        assertFalse(LOOKUP_CALL.matcher("rule(\"r1\", ").find());
        assertTrue(usedKeys().contains("money.error.grouping"), "ключ модели используется через Texts.get");
        assertTrue(usedKeys().contains("sample.rule.salary"), "ключ, переданный литералом в метод, считается используемым");
    }

    /** @return ключи, на которые есть литерал в исходниках или которые строятся из таблиц */
    private static Set<String> usedKeys() {
        Set<String> catalog = UiText.keys();
        Set<String> used = new LinkedHashSet<>(dynamicKeys());
        for (Path root : SOURCE_ROOTS) {
            for (JavaSourceScanner.Literal literal : JavaSourceScanner.literals(root)) {
                if (catalog.contains(literal.raw())) {
                    used.add(literal.raw());
                }
            }
        }
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
