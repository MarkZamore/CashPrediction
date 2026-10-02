package ru.cashprediction.core.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.text.CoreModuleDir;

/**
 * Не даёт новым Java-отрисовщикам повторить бизнес-алгоритмы ядра.
 * Наличие интерфейсов и их поведение проверяются отдельно настоящими сценариями, не этой статической проверкой.
 */
class ThinClientArchitectureTest {
    private static final Set<String> BUSINESS = Set.of("PlanDocument", "ForecastEngine", "OccurrenceGenerator",
            "GoalCalculator", "PlanRepository", "PlanMarkdownReader", "PlanMarkdownWriter");

    @Test void newJavaRenderersDoNotOwnBusinessAlgorithmsOrPlanStorage() throws Exception {
        Path root = CoreModuleDir.get().getParent();
        List<String> found = new ArrayList<>();
        for (String module : List.of("ui-fx", "ui-swing", "web")) {
            Path main = root.resolve(module).resolve("src/main/java");
            assertTrue(Files.isDirectory(main), main.toString());
            // До S4 прежние пакеты остаются для сравнения и не входят в новое архитектурное правило.
            try (var paths = Files.walk(main)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                    Path relative = main.relativize(path);
                    boolean renderer = false;
                    for (Path part : relative) if (part.toString().equals("ui")) renderer = true;
                    if (renderer) {
                        for (String name : references(Files.readString(path))) found.add(path + ": " + name);
                    }
                }
            }
        }
        assertEquals(List.of(), found, "Business algorithms and plan storage must remain in core");
    }

    @Test void importsFullyQualifiedCallsAndConstructionAreDetectedButProseIsNot() throws Exception {
        var found = references("""
                import ru.cashprediction.core.document.PlanDocument;
                class Renderer {
                    // ForecastEngine must remain in core.
                    String explanation = "PlanMarkdownReader";
                    Object a = new PlanDocument(null, null);
                    Object b = ru.cashprediction.core.forecast.ForecastEngine.compute(null);
                }
                """);
        assertTrue(found.contains("PlanDocument"));
        assertTrue(found.contains("ForecastEngine"));
        assertFalse(found.contains("PlanMarkdownReader"));
    }

    @Test void ordinaryCoreUiModelsAndIntentsAreAllowed() throws Exception {
        assertEquals(List.of(), references("""
                import ru.cashprediction.core.app.UiIntents;
                import ru.cashprediction.core.ui.form.FormSession;
                class Renderer { UiIntents intents; FormSession form; }
                """));
    }

    /** Разбирает только синтаксис: зависимости клиента и графический инструментарий компилятору не нужны. */
    private static List<String> references(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Architecture checks require the development JDK");
        var input = new SimpleJavaFileObject(URI.create("string:///Renderer.java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        List<String> found = new ArrayList<>();
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null, List.of(input));
            var scanner = new TreeScanner<Void, Void>() {
                @Override public Void visitIdentifier(IdentifierTree node, Void ignored) {
                    record(node.getName().toString());
                    return super.visitIdentifier(node, ignored);
                }
                @Override public Void visitMemberSelect(MemberSelectTree node, Void ignored) {
                    record(node.getIdentifier().toString());
                    return super.visitMemberSelect(node, ignored);
                }
                private void record(String name) { if (BUSINESS.contains(name) && !found.contains(name)) found.add(name); }
            };
            for (var tree : task.parse()) scanner.scan(tree, null);
        }
        return List.copyOf(found);
    }
}
