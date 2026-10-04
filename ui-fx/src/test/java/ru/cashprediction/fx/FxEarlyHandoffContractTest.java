package ru.cashprediction.fx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет токены конкретной denied-ветки main без toolkit, fork, компиляции или новых модулей JDK. */
class FxEarlyHandoffContractTest {
    /** Реальная denied-ветка явно завершает процесс до return и до запуска интерфейса. */
    @Test void deniedBeforeUiExitsBeforeReturning() throws Exception {
        Path source = Path.of(System.getProperty("fx.basedir", "."), "src/main/java/ru/cashprediction/fx/FxMain.java");
        assertTrue(exitsDeniedBranch(Files.readString(source)), "beforeUi=false must call exitAfterLaunch before return");
    }

    /** Прежний простой return отвергается, даже если комментарий содержит правильный вызов. */
    @Test void returnOnlyAndCommentAreRejected() {
        assertFalse(exitsDeniedBranch("class FxMain { public static void main(String[] args) {"
                + " if (!updates.beforeUi()) { /* exitAfterLaunch(); */ return; } } }"));
    }

    /** Неправильный порядок, другая ветка и строковый литерал не удовлетворяют контракту. */
    @Test void wrongOrderWrongBranchAndLiteralAreRejected() {
        assertFalse(exitsDeniedBranch("class FxMain { public static void main(String[] args) {"
                + " if (!updates.beforeUi()) { return; exitAfterLaunch(); } } }"));
        assertFalse(exitsDeniedBranch("class FxMain { public static void main(String[] args) {"
                + " if (updates.beforeUi()) { exitAfterLaunch(); return; } } }"));
        assertFalse(exitsDeniedBranch("class FxMain { public static void main(String[] args) {"
                + " String s=\"if (!updates.beforeUi()) { exitAfterLaunch(); return; }\"; } }"));
        assertFalse(exitsDeniedBranch("class FxMain { public static void main(String[] args) { return; }"
                + " void other() { if (!updates.beforeUi()) { exitAfterLaunch(); return; } } }"));
        assertTrue(exitsDeniedBranch("class FxMain { public static void main(String[] args) {"
                + " if (!updates.beforeUi()) { exitAfterLaunch(); return; } } }"));
    }

    /** Ограничивает поиск телом известного main; не принимает вызов из другого метода после него. */
    private static boolean exitsDeniedBranch(String source) {
        List<String> code = tokens(source);
        List<String> main = tokens("public static void main(String[] args) {");
        List<String> denied = tokens("if (!updates.beforeUi()) { exitAfterLaunch(); return; }");
        for (int i = 0; i + main.size() <= code.size(); i++) {
            if (!code.subList(i, i + main.size()).equals(main)) continue;
            int start = i + main.size(), depth = 1, end = start;
            while (end < code.size() && depth > 0) {
                String token = code.get(end++);
                if (token.equals("{")) depth++;
                if (token.equals("}")) depth--;
            }
            if (depth != 0) return false;
            for (int p = start; p + denied.size() < end; p++)
                if (code.subList(p, p + denied.size()).equals(denied)) return true;
            return false;
        }
        return false;
    }

    /** Читает идентификаторы и знаки без комментариев и литералов; это узкий контракт, не Java parser. */
    private static List<String> tokens(String source) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i + 2); i = end < 0 ? source.length() : end + 1; continue;
            }
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2); i = end < 0 ? source.length() : end + 2; continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c; i++;
                while (i < source.length()) {
                    char value = source.charAt(i++);
                    if (value == '\\') { if (i < source.length()) i++; }
                    else if (value == quote) break;
                }
                result.add("<literal>"); continue;
            }
            int start = i++;
            if (Character.isJavaIdentifierStart(c))
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            result.add(source.substring(start, i));
        }
        return result;
    }
}
